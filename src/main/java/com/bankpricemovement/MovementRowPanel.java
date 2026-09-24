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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.api.Constants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

/**
 * One bank item in the sidebar list: a 213 x 62 card with the item's picture on the left and THREE text lines
 * beside it (the Q4 format, {@code docs/handoff/row-format-Q4-2026-09-20.md}, over addendum N section 2 "Rows"
 * and section 4.5, the "Ticker" row; {@code docs/bank-price-movement-addendum-N-2026-09-09.md}). This anatomy
 * OVERRIDES contract C31's 213 x 40 strip and its two-number line.
 *
 * <pre>
 * +-+----+-----------------------------------+
 * |#|    | Divine ranging poti...(3)         |
 * |#|icon| 32.3k           +3.0k      +10.2% |
 * |#|    | 7 x 4,618       +428              |
 * +-+----+-----------------------------------+
 * </pre>
 *
 * <p><b>Which line says what.</b> Line 1 is the name, the row's headline. Line 2 is the STACK: what the whole
 * holding is worth, then what the holding moved and the percentage. Line 3 is ONE item: the working behind
 * line 2 - "7 x 4,618" - and what one item moved. The two gp figures stand in one right-aligned
 * {@code GP_COLUMN}, the stack's directly over the item's, which is the whole point of the arrangement: a
 * reader who wants the per-item number never has to divide.
 *
 * <p>The user rejected every single-line version of this for exactly that reason - a row printing a per-ITEM
 * price beside an "x7" and then a per-ITEM gp move "makes the user have to do mental math" - and the three
 * lines are what pay for it, at 14 px of extra height per row.
 *
 * <p><b>The word "Total" is gone, and that undoes Q4.6 / AN2 and AN6</b> (addendum AO, line AO2). The Q4
 * format opened line 2 with "226k Total", and AN6 then made that word UNCONDITIONAL on the user's own
 * instruction, because a build that printed it only where it fitted dropped it on exactly the richest rows.
 * The user has now seen the word in a client over a real bank - "1,851 Total", "68.6k Total", "5,750 Total"
 * down the page - and asked for it to be removed. So line 2 is the stack's value and the two figure columns,
 * nothing else, and the argument AN6 won is simply no longer being had: the label is not conditional, it does
 * not exist. What told a reader which figure was which is now the arrangement alone - line 2's number is the
 * big one and line 3 spells out the sum it came from - and the block the cell opens still labels every figure
 * in words ({@link #detail}). This is recorded rather than quietly applied because a format that loses a word
 * it once argued for reads as a bug to the next person who opens the file.
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
 * {@link #TEXT_WIDTH}. The right-hand figures are no longer FITTED, they are COLUMNS: {@code GP_COLUMN} and
 * {@code PCT_COLUMN} are pinned widths, so the two lines' figures line up whatever they say, and the left of
 * each line is fitted into what the columns leave. That is what replaced the M4 count idiom - under it the
 * stack text was added only when it happened to fit, and a figure that comes and goes down a list reads as a
 * bug. (That rule is about the COLUMNS and still holds; the word addendum AN argued the same way about is a
 * separate question, and AO2 answered it by deleting the word outright rather than making it conditional
 * again.) The name is fitted to the whole {@link #TEXT_WIDTH} through {@link Widgets#setFittedName}, which keeps a
 * potion's dose when it has to cut ("Super combat pot...(4)"), because the four doses are otherwise cut to one
 * identical headline. Everything cut is whole in the block the row opens.
 *
 * <p><b>The picture.</b> {@code ItemManager.getImage(id, quantity, stackable)} answers an
 * {@link AsyncBufferedImage} that may still be blank
 * ({@code runelite-client/src/main/java/net/runelite/client/game/ItemManager.java:527-529}: filled in later
 * on the client thread); {@link AsyncBufferedImage#addTo(JLabel)} ({@code AsyncBufferedImage.java:88-91})
 * registers the repaint for when it lands, which is how {@code GrandExchangeItemPanel.java:105} and
 * {@code LootTrackerBox.java:304} show theirs. The reference is kept on the row (the manager's cache holds
 * 128 images, {@code ItemManager.java:221-222}) so the picture survives the cache moving on.
 *
 * <p><b>Mouse.</b> The hover colour and the click are on the row AND on every child: a component with a
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
 * <p>The block the row opens stays load-bearing, for a narrower reason than before. Its figures are EXACT and
 * the face's are not: {@code signedGp} prints "+3.4k" where the block prints "+3,432", because a gp column
 * wide enough for five digits and a comma does not fit beside a {@code PCT_COLUMN}. Every figure on the face
 * was already compact - the prices, the stack total - so the loss is one step further in the same direction,
 * but it IS a loss of precision on the face and the block is where the reader gets it back.
 *
 * <p><b>That block is written the first time the row opens, not when the row is built</b> (addendum AS,
 * {@code docs/handoff/plan-AS-bank-hold-2026-09-21.md} section 2.1). Every build from AI to AR wrote it in the
 * constructor and hid it, on the belief that a label costs nothing while it is invisible. It does not: a label
 * turns HTML into a view tree the moment it is handed the text, shown or not - {@code BasicLabelUI} answers
 * every "text" change with {@code BasicHTML.updateRenderer} (JDK 17), and the {@code FlatLabelUI} under
 * RuneLite's look and feel does the same - and the lag investigation measured that at about 1 MB and 1 ms a row,
 * some 181 ms and 275 MB of garbage for the 250-row page a bank change rebuilds, nearly all of it for blocks
 * nobody had opened. So the row still builds the LABEL, empty and hidden, because it has to be a child from the
 * start - the click that shuts an open block and the right-click menu are installed on every child as the row
 * is built - and what waits is the TEXT. {@link #applyExpansion()} writes it on the first opening, before it
 * measures the block, so the cell still grows in one beat and to the height it always grew to. A row rebuilt
 * from a seam that says it is open writes it as it is built; shutting the block keeps it; a second opening
 * reuses it. The long description no sidebar draws ({@link #tooltipHtml()}) was written for every row too,
 * and now waits for a caller that asks.
 *
 * <p>The change is signed by the gp figure, not by the percentage (L2): a fall too small to survive
 * truncation still reads "-0.0%" and still paints red, exactly as the GE site's own row does.
 *
 * <p><b>Three switches are handed to the row</b> ({@link ViewOptions}, at build time - a row is built once and
 * never re-read, so a switch that moves rebuilds the page). The two texts written later since addendum AS, the
 * open block and the long description, are written from the switches the row was handed and kept, so a late
 * text says exactly what an early one would have. There were four until addendum AO: the fourth was
 * {@code holdingOnRows}, which chose between the per-stack reading and the per-item one because only one of
 * them fitted on a 48 px row, and the three-line face prints BOTH. It reached nothing here after the Q4
 * format and the key is now deleted (AO1), so its bullet, the {@code priceText}, {@code gpText} and
 * {@code quantityText} statics and the private {@code holding} predicate that read it have gone with it.
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
	 * The row's height (the Q4 format, over addendum N section 2 and section 3 §4 and contract C31's 40): 3 px
	 * of top padding, the name line, the stack line, the item line and 2 px below. It was 48 while the face
	 * carried two lines; the third costs 14 px, which is the price of never making the reader divide.
	 */
	public static final int ROW_HEIGHT = 62;
	/**
	 * The picture cell: exactly the size the client draws an item sprite at ({@link Constants#ITEM_SPRITE_WIDTH}
	 * x {@link Constants#ITEM_SPRITE_HEIGHT}, 36 x 32), named from the client rather than typed so the two can
	 * never drift apart again.
	 *
	 * <p>They did once. Addendum AN narrowed this cell to 32 to give the text block 4 px, on the theory that a
	 * sprite's edges are mostly transparent and only a whip or a godsword would notice. The first live look said
	 * otherwise (addendum AP): the label centres the picture, so 2 px went off EACH side, and the client paints
	 * a stackable item's quantity - "32590" on a stack of water runes, "5000" on lizardman fangs - hard against
	 * the sprite's LEFT edge, so every stack number in the list lost part of its first digit. It is the only
	 * number on the row the game draws rather than this class, and cutting it is worse than cutting a name.
	 */
	public static final int ICON_WIDTH = Constants.ITEM_SPRITE_WIDTH;
	public static final int ICON_HEIGHT = Constants.ITEM_SPRITE_HEIGHT;
	/**
	 * How far right of its frame's natural place every picture is drawn, so the ARTWORK - not the frame - stands
	 * midway between the rail and the name (addendum AR).
	 *
	 * <p>The client does not centre an item's artwork in its 36 px frame. Measured on the user's own screenshot
	 * of four rows, the art's centre sat at x = 15 of the frame on three of them (two potions and a stack of
	 * unidentified minerals) and at 13 on the fourth, where the frame's middle is 17.5. With the frame itself
	 * centred (addendum AQ) the pictures therefore still read 3 px left of the middle - which is what the user
	 * saw and asked about. It is a property of the game's item art, the same in the bank and the inventory, and
	 * nearly constant across items, so one constant corrects it; centring each item on its own visible pixels
	 * would not work, because a stackable's quantity is painted into the same image and would drag it sideways.
	 *
	 * <p>The air is taken from the gap after the picture, not the text: {@link #GAP} drops by exactly this much,
	 * so {@link #TEXT_WIDTH} and every name keep their room. The frame's right-hand columns are the ones the art
	 * leaves empty, so nothing drawn comes closer to the name than the art's own margin.
	 *
	 * <p><b>2, and not the 3 the measurement gave.</b> Addendum AR landed 3; the user looked at it in the client
	 * and said "shift it 2 pixels to the left" (AR3), then "shift it 1 pixel to the right" (AR4). The eye in the
	 * client is the judge of where a picture looks centred - the measurement was four rows of one screenshot, and
	 * a picture is weighed by where its mass sits, not by its bounding box - so this is the user's number, and the
	 * test that pins it says so.
	 */
	static final int ART_NUDGE = 2;
	/** The picture's cell: the whole sprite, right-aligned, with {@link #ART_NUDGE} of air on its left. */
	static final int PICTURE_WIDTH = ICON_WIDTH + ART_NUDGE;
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

	/**
	 * The card's padding inside the coloured edge (N section 3 §4): 213 - 3 - 3 - 6 = 201 px of inner width.
	 *
	 * <p>The LEFT inset is one half of the picture's frame, and {@link #GAP} is the other: the two are equal so
	 * the picture stands exactly midway between the rail and the text (addendum AQ, the user: "the edge of the
	 * green line and the start of the words ... solve for the middle point between them and have all imgs at the
	 * center point"). The Q4 format had them at 2 and 4, which put every picture 1 px left of the middle before
	 * the name's own first-letter margin made it look like two.
	 */
	private static final Insets PADDING = new Insets(3, 3, 2, 6);
	/**
	 * The face's own box, inside the card's rail and padding (AI). Every build before AI pinned {@code this}
	 * to {@link #ROW_WIDTH} x {@link #ROW_HEIGHT} and laid the icon and the text straight into it; the face now
	 * holds exactly that box, so a collapsed row is the same arrangement of the same pixels. The Q4 format grew
	 * the box - a third text line - and changed nothing about the seam.
	 */
	static final int INNER_WIDTH = ROW_WIDTH - Widgets.EDGE_WIDTH - PADDING.left - PADDING.right;
	static final int FACE_HEIGHT = ROW_HEIGHT - PADDING.top - PADDING.bottom;
	/** Air between the face and the detail block of an open row, in px. */
	private static final int DETAIL_GAP = 4;
	/**
	 * Between the picture's cell and the text block. Addendum AQ made it equal to {@code PADDING.left} (3), which
	 * centred the FRAME; addendum AR moved {@link #ART_NUDGE} of it to the other side of the picture, to centre
	 * the ARTWORK where the user's eye puts it. The total either side of the picture is unchanged, so the text
	 * never moved.
	 */
	private static final int GAP = 3 - ART_NUDGE;
	/**
	 * What the three text lines share: the row minus the rail, the padding, the picture and the gap after it.
	 * 213 - 3 - 3 - 6 - (36 + 2) - 1 = 162, against the 156 the two-line face had and the 124-134 px the name had
	 * before addendum N. The 6 px came from the left margins (the padding and the gap); addendum AN took 4 more
	 * from the picture and addendum AP gave them back, because the picture could not spare them.
	 */
	static final int TEXT_WIDTH = ROW_WIDTH - Widgets.EDGE_WIDTH - PADDING.left - PADDING.right
		- PICTURE_WIDTH - GAP;
	/**
	 * Between the stack's value on the left of line 2 and the figures on the right. Six again, as it was before
	 * the Q4 format: it was cut to three only to buy the four px {@link #GP_COLUMN} needed once line 2 carried a
	 * five-character stack value, a word, a five-character gp figure and a percentage all at once, and addendum
	 * AO took the word away. Line 2 has 31 px back, so the gap is the one the rest of the panel uses rather than
	 * the narrowest one that fitted.
	 */
	private static final int LINE2_GAP = 6;
	/** Between a gp figure and whatever stands to its right - the percentage on line 2, the spacer on line 3. */
	private static final int FIGURE_GAP = 6;
	/**
	 * The pinned width of the gp figure's box on BOTH figure lines, {@link #FIGURE_GAP} included. It is a column
	 * and not a fitted label because the point of the Q4 format is that the stack's move stands directly over
	 * one item's: two right-aligned labels of the same width do that on every row of the list, and two fitted
	 * ones would wander by a few px per row and read as a wobble.
	 *
	 * <p>42 = the 36 px {@link #signedGp}'s widest output measures at {@link #GP_SIZE}, plus {@link #FIGURE_GAP}.
	 * The widest is not the obvious one: {@code QuantityFormatter} caps a compact amount at five characters, so
	 * "+1,00m" (what 999,999,999 comes out as) and "+1.66m" both measure 36 and nothing measures more. It was
	 * 38 when this format was first drawn, which held every figure in the fixture and clipped a real bank's
	 * millions to "-1.6...". That is why {@link #signedGp} is compact in the thousands as well: without it the
	 * column wants 48 and there is not 48 to give.
	 */
	private static final int GP_COLUMN = 42;
	/**
	 * The pinned width of the percentage's box, the outermost column on line 2 and an empty spacer on line 3.
	 * Wider than {@link #GP_COLUMN} because {@link #PCT_SIZE} is the biggest face on the row and "-100.0%" is
	 * the worst case it has to hold whole.
	 */
	private static final int PCT_COLUMN = 54;
	/**
	 * The multiplication sign of line 3's working. A lower-case ASCII "x" and deliberately not "&times;": these
	 * faces are bitmap at these sizes and their glyph for it is unreliable, and a row is the wrong place to
	 * discover that on someone else's machine.
	 */
	private static final String TIMES = "x";

	/** T3 of addendum N section 3 §2: the item name, bold, white, the row's headline. */
	private static final int NAME_SIZE = 14;
	/**
	 * T4, as the Q4 format reassigned it: the STACK's value, line 2's opening figure. One step down from 14,
	 * which is what the word "Total" beside it was paid for. Addendum AO deleted the word and left the size
	 * where it was: the size is not an argument about the word, it is what keeps line 2's figure the second
	 * loudest thing on the row behind the name, and raising it now would be a change to the drawn row that
	 * nobody asked for.
	 */
	private static final int PRICE_SIZE = 13;
	/** T7: line 3's working, and the {@value #ALCH_TAG} tag standing in for an untradeable row's figures. */
	private static final int SMALL_SIZE = 11;
	/**
	 * The percentage. The biggest face on the row and the one the eye is meant to land on first - the user tried
	 * this against a same-sized gp figure and asked for the two to "be more isolated from each other", and SIZE
	 * is what separated them: 15 px bold against {@link #GP_SIZE}.
	 */
	private static final int PCT_SIZE = 15;
	/**
	 * The gp figures, on both lines. It was 12 (N section 4) and then, once there were two of them stacked in
	 * one column beside a 15 px percentage, 10: small enough that {@link #GP_COLUMN} fits beside
	 * {@link #PCT_COLUMN}, and quiet enough that the percentage wins the line.
	 */
	private static final int GP_SIZE = 10;
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

	/**
	 * How many times {@link #detail} and the four-argument {@link #tooltip} have run in this JVM - the evidence
	 * addendum AS is judged on. Only the tests read them: a page of rows built and thrown away must leave both
	 * where they were, and opening one row must move the first by exactly one. A count rather than a look at the
	 * label, because a text that was built and then thrown away costs just as much and leaves no label behind.
	 *
	 * <p>Atomic because the two builders are public statics a test may call from any thread. The cost is one
	 * increment per text written, and a row writes at most one of each.
	 */
	private static final AtomicLong DETAIL_BUILDS = new AtomicLong();
	private static final AtomicLong TOOLTIP_BUILDS = new AtomicLong();

	private final MovementRow row;
	/** Kept so the sprite stays referenced while the row is on screen (see the class comment). */
	@Nullable
	private final AsyncBufferedImage icon;
	private final Consumer<String> browser;
	private final JLabel iconLabel;
	private final JLabel nameLabel;
	/** Line 2's opening figure. The Q4 format made it the STACK's value; the field keeps its older name. */
	private final JLabel priceLabel;
	private final JLabel changeLabel;
	/** Line 2's gp figure: what the whole STACK moved. Line 3's is built in {@link #itemLine} and not kept. */
	private final JLabel gpLabel;
	private final Color rail;
	/**
	 * The window, the baseline day and the switches this row was built under. Kept since addendum AS because two
	 * texts are now written AFTER the constructor - the open block and the long description - and each has to say
	 * exactly what it would have said had it been written in the constructor. All three are immutable values, so
	 * a late text and an early one are the same text.
	 */
	@Nullable
	private final MovementWindow window;
	@Nullable
	private final LocalDate thenDay;
	private final ViewOptions view;
	/**
	 * The long description of addendum K8, which no sidebar has drawn since addendum AK moved the open cell to
	 * {@link #detail}: written on the first call to {@link #tooltipHtml()} and kept, null until then. Every build
	 * from AI to AR wrote it in the constructor, for every row of every page, and it was garbage the moment the
	 * constructor returned (addendum AS).
	 */
	@Nullable
	private String tooltip;
	/** The {@link #ROW_HEIGHT} px cell every build before AI called the row: the picture, the name, the figures. */
	private final JPanel face;

	/**
	 * The block under the face, shown only while the row is open (AI). EMPTY until the row is first opened
	 * (addendum AS): {@link #applyExpansion()} writes its text then, and never again.
	 */
	private final JLabel detailLabel;
	/**
	 * Whether {@link #detailLabel} carries its text yet (addendum AS). Set on the first opening and never cleared,
	 * because shutting the block only hides it: a second opening shows the same text rather than parsing the same
	 * HTML a second time.
	 */
	private boolean detailBuilt;

	/** Where this row's clicked/unclicked state lives, so it survives the next publish rebuilding the page. */
	private final Expansion expansion;
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
	 *                NOTHING on the face reads them since the Q4 format - whether an untradeable stack is LISTED
	 *                at all is the service's decision (Q5), and the three-line face prints both the stack and
	 *                the item reading, which is what left {@code holdingOnRows} with nothing to choose and is
	 *                why addendum AO could delete that key outright. Since addendum T
	 *                {@code livePrices} reaches the TOOLTIP
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

	/**
	 * The list builder's constructor since addendum AG.
	 *
	 * @param expansion where the row's clicked/unclicked hover state lives - the PANEL's, so it outlives the
	 *                  rebuild every publish performs; null gives the row one of its own
	 */
	public MovementRowPanel(MovementRow row, @Nullable AsyncBufferedImage icon, MovementWindow window,
		@Nullable LocalDate thenDay, @Nullable ViewOptions options, @Nullable Expansion expansion)
	{
		this(row, icon, window, thenDay, options, LinkBrowser::browse, expansion);
	}

	/** @param browser what the two right-click entries hand their URL to; the tests record, the client browses */
	MovementRowPanel(MovementRow row, @Nullable AsyncBufferedImage icon, MovementWindow window,
		@Nullable LocalDate thenDay, @Nullable ViewOptions options, Consumer<String> browser)
	{
		this(row, icon, window, thenDay, options, browser, null);
	}

	/**
	 * @param expansion where the clicked/unclicked state of addendum AG lives; null gives the row one of its
	 *                  own, which is what every caller that never rebuilds a page wants - the row then simply
	 *                  starts short and toggles for as long as it exists
	 */
	MovementRowPanel(MovementRow row, @Nullable AsyncBufferedImage icon, MovementWindow window,
		@Nullable LocalDate thenDay, @Nullable ViewOptions options, Consumer<String> browser,
		@Nullable Expansion expansion)
	{
		this.row = Objects.requireNonNull(row, "row");
		this.icon = icon;
		this.browser = Objects.requireNonNull(browser, "browser");
		this.expansion = expansion == null ? new OwnExpansion() : expansion;
		// AS: kept rather than spent here - the open block and the long description are both written later.
		this.window = window;
		this.thenDay = thenDay;
		this.view = options == null ? ViewOptions.DEFAULT : options;
		this.rail = railColor(row);

		// AI: the cell is a FACE over a DETAIL block, both inside the one card border, so an opened row grows
		// downward as a single cell with its rail running the whole new height - rather than a second widget
		// appearing under a row that stayed 48 px. The face is what every build before AI called the row, and
		// it keeps its exact geometry, which is what lets a collapsed list stay pixel-identical.
		setLayout(new BorderLayout(0, 0));
		setBorder(Widgets.card(rail, PADDING));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);

		face = transparent(new BorderLayout(GAP, 0));
		Widgets.fixed(face, INNER_WIDTH, FACE_HEIGHT);
		add(face, BorderLayout.NORTH);

		// AI: the detail, under the face and inside the same border - created here, EMPTY and hidden, and given
		// its text on the row's first opening (addendum AS, applyExpansion). The LABEL cannot wait: the click
		// listener and the right-click menu are installed on every child below, and a block added later would
		// carry neither, so a click on an open block would no longer shut it. The TEXT can: this comment used to
		// say a hidden label costs nothing and that its height had to be measurable before the click, and
		// neither was true - a label parses its HTML the moment it is handed it, shown or not, which was the lag
		// of every bank change, and the height is only ever measured AT an opening, where the text is now
		// written first, so the cell still grows in one beat.
		detailLabel = Widgets.label("", SMALL_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		detailLabel.setVerticalAlignment(SwingConstants.TOP);
		detailLabel.setBorder(new EmptyBorder(DETAIL_GAP, 0, 0, 0));
		detailLabel.setVisible(false);
		add(detailLabel, BorderLayout.CENTER);
		Widgets.fixed(this, ROW_WIDTH, ROW_HEIGHT);

		iconLabel = new JLabel();
		// RIGHT, in a cell ART_NUDGE wider than the sprite: the whole sprite is drawn (AP) and the air goes on
		// its left, which is what puts the game's off-centre artwork in the middle (AR).
		iconLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		iconLabel.setVerticalAlignment(SwingConstants.CENTER);
		Widgets.fixed(iconLabel, PICTURE_WIDTH, ICON_HEIGHT);
		if (icon != null)
		{
			icon.addTo(iconLabel);
		}
		face.add(iconLabel, BorderLayout.WEST);

		// Line 1: the name across the whole block - the row's headline, and the only thing on its line. Fitted
		// through setFittedName, so a potion keeps its dose when it is cut (all four of "Super combat potion(1..4)"
		// are wider than the block, and the plain cut made one string of them).
		nameLabel = Widgets.label("", NAME_FONT, Color.WHITE);
		Widgets.setFittedName(nameLabel, row.name(), TEXT_WIDTH);

		// Line 2 is the STACK. The right-hand columns are built first because the left is fitted into what they
		// leave: the percentage, the gp figure beside it - or, on an untradeable row, the grey "alch" tag
		// standing in for both (Q5).
		final Color moveColour = textChangeColor(row);
		changeLabel = isAlch(row)
			? Widgets.label(ALCH_TAG, SMALL_FONT, ColorScheme.LIGHT_GRAY_COLOR)
			: Widgets.label(changeText(row), PCT_FONT, moveColour);
		// AL: the gp figure takes the QUIET role - the same green or red, mixed toward the row's background - so
		// the percentage beside it is the one the eye lands on. The user, looking at a full list: "the change in
		// gp ... and the percent change ... be more isolated from each other".
		gpLabel = Widgets.label(stackGp(row), GP_FONT, quietChangeColor(row));
		// Every gap on this line is an inset and never a layout hgap: BorderLayout charges its hgap for the WEST
		// and the EAST child alike, so a 6 px hgap here would quietly cost 12 px and the fit would hand the
		// price 6 px it does not have (measured, 2026-09-09, when the block was 156 px wide: the widest gp row
		// asked for 160 of it).
		final JPanel figures = transparent(new BorderLayout(0, 0));
		figures.setBorder(new EmptyBorder(0, LINE2_GAP, 0, 0));
		gpLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		gpLabel.setBorder(new EmptyBorder(0, 0, 0, FIGURE_GAP));
		Widgets.fixed(gpLabel, GP_COLUMN, gpLabel.getPreferredSize().height);
		figures.add(gpLabel, BorderLayout.WEST);
		changeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		Widgets.fixed(changeLabel, PCT_COLUMN, changeLabel.getPreferredSize().height);
		figures.add(changeLabel, BorderLayout.EAST);

		// ...then the stack's value into what the columns left. AO2 took the word "Total" off this line, so the
		// price is fitted to the whole of the rest of it; the fit stays because the columns are pinned and the
		// price is the only thing on the row that can be asked to give way. Nothing in a real bank reaches it -
		// the widest stack value measures 36 and this leaves far more - but the day a formatter grows a
		// character, a cut price is a readable failure and an overlap is not.
		priceLabel = Widgets.label("", PRICE_FONT, Color.WHITE);
		Widgets.setFitted(priceLabel, stackText(row), TEXT_WIDTH - figures.getPreferredSize().width);

		final JPanel line2 = transparent(new BorderLayout(0, 0));
		line2.add(priceLabel, BorderLayout.WEST);
		line2.add(figures, BorderLayout.EAST);

		final JPanel line3 = itemLine(row);
		// Line 2's three labels only: line 3 is a line of its own, below them, and has nothing to sit level with.
		alignBaselines(priceLabel, gpLabel, changeLabel);

		final JPanel text = transparent(new BorderLayout(0, 0));
		text.add(nameLabel, BorderLayout.NORTH);
		text.add(line2, BorderLayout.CENTER);
		text.add(line3, BorderLayout.SOUTH);
		face.add(text, BorderLayout.CENTER);

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

			/**
			 * AG: the left button toggles the hover's length. The right button is untouched - it opens the two
			 * entries of K8 through {@code setComponentPopupMenu}, and a popup trigger must never also toggle.
			 */
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger()
					&& e.getSource() instanceof JComponent)
				{
					toggleExpanded((JComponent) e.getSource(), e);
				}
			}
		};
		addMouseListener(hover);
		for (JComponent c : children())
		{
			c.setInheritsPopupMenu(true);
			c.addMouseListener(hover);
		}
		clearHovers();
		// AI: draw whatever the seam already says. A page is rebuilt from new instances on every publish - a
		// refresh, a bank opening, the half-hourly recheck - so a row whose item the reader had opened must
		// come back OPEN. Without this the cell stood 48 px tall while expanded() answered true, and the next
		// click would have closed a row that looked shut: two clicks to reopen it. Since addendum AS this is
		// also where such a row writes its block, so a page costs one block per row the reader has OPEN and
		// none for the rest.
		applyExpansion();
	}

	/**
	 * A row says nothing on hover (AI). Its description is the block the click opens, so a tooltip would be a
	 * second copy of it - and a hover over every row the pointer crossed is what the reader asked to be rid of.
	 *
	 * <p>Called once, at the end of construction, because two of the labels give themselves a hover:
	 * {@code Widgets.setFittedName} and {@code Widgets.setFitted} hang the FULL text on any label they have to
	 * cut, which is how a cut name and a cut price would still have spoken. A silent row has to be silent
	 * wherever the pointer can land on it.
	 */
	private void clearHovers()
	{
		setToolTipText(null);
		for (JComponent c : children())
		{
			c.setToolTipText(null);
		}
	}

	/** The label column of the open cell (AK), in the order the block prints them. */
	public static final String L_NOW = "Worth now";
	public static final String L_WAS = "Was";
	public static final String L_HAVE = "You have";
	/** The change's label carries its WINDOW, so the figure beside it says what it is measuring: "Change 7d". */
	public static final String L_CHANGE = "Change ";
	/**
	 * What an untradeable stack's cell says under its figures (AK). It does NOT repeat the value - "Worth now"
	 * is two lines above it, and {@link #untradeableLine} spells the same number out again because it was
	 * written for a hover that had no such line.
	 */
	public static final String ALCH_NOTE = "Untradeable - High Alchemy value, not in the movement figures";

	/**
	 * The open cell's description (addendum AK): four labelled lines, and a note only where one carries
	 * something the four cannot.
	 *
	 * <pre>
	 * Worth now    4,618 gp each
	 * Was          4,190 gp  (19 Sep)
	 * You have     7  =  32,326 gp
	 * Change 1d    +428 each  +10.2%
	 * </pre>
	 *
	 * <p><b>Why it was rewritten.</b> Until AK this block was the old hover's prose, and the user could not read
	 * it: "Live traded price: 4,618 gp (buy 4,796, sell 4,440; 6,565 traded yesterday)" is seventy-five
	 * characters in a cell about thirty-two wide, so it wrapped three times and the figure that mattered was
	 * buried inside its own parenthesis. A label column fixes both faults - the eye runs down one edge instead
	 * of hunting along a sentence, and every line carries one idea.
	 *
	 * <p><b>The change names its window</b> ({@link #L_CHANGE} plus {@code window.label()}), the user's own
	 * correction: a line reading "Change" beside a figure says nothing about whether it is a day's move or half
	 * a year's, and the lit chip is at the top of the sidebar rather than beside the number.
	 *
	 * <p><b>The buy/sell spread is gone.</b> It was the densest thing here and it serves a flipper, who has the
	 * Grand Exchange open anyway; the traded VOLUME survives as a note, because that is what says whether a live
	 * price can be trusted. Nothing else was dropped: an alch row still says it is untradeable, a parts row
	 * still names its parts, a carried stack still says where it is, and a row the live rule refused still says
	 * which check refused it.
	 *
	 * <p>A table rather than padded spaces, because these faces are proportional: "You have" and "Was" are
	 * different widths in pixels however many spaces follow them, and only a column lines the figures up.
	 *
	 * <p><b>A row writes this once, on its first opening</b> (addendum AS; {@link #applyExpansion()}), and not as
	 * it is built: a label turns the HTML into a view tree the moment it is handed it, and a page of 250 rows was
	 * paying for 250 blocks nobody had opened. Every call is counted ({@link #detailBuilds()}), which is how the
	 * tests prove that a page writes none.
	 *
	 * @param showName whether to bold the item's name above the table - true only when the row's face had to cut
	 *                 it, which is the one case a reader cannot read it from the cell they are looking at
	 */
	public static String detail(MovementRow row, @Nullable MovementWindow window, @Nullable LocalDate thenDay,
		@Nullable ViewOptions options, boolean showName)
	{
		Objects.requireNonNull(row, "row");
		DETAIL_BUILDS.incrementAndGet();
		final ViewOptions view = options == null ? ViewOptions.DEFAULT : options;
		final boolean live = view.livePrices();
		final boolean alch = isAlch(row);
		// The defaulted window is used by BOTH lines that name one - the "Was" day and the "Change" label. They
		// read a null window the same way or the block would say "Change 1d" over a day D1 never chose.
		final MovementWindow w = window == null ? MovementWindow.DEFAULT : window;

		final StringBuilder sb = new StringBuilder(384);
		sb.append("<html><div width=\"").append(INNER_WIDTH).append("\">");
		if (showName)
		{
			sb.append("<b>").append(Widgets.escapeHtml(row.name())).append("</b>");
		}
		sb.append("<table cellpadding=0 cellspacing=0>");

		cell(sb, L_NOW, row.unitPrice() == null
			? MovementMath.DASH : MovementMath.formatExact(row.unitPrice()) + " gp each", null);

		if (!alch)
		{
			cell(sb, L_WAS, (row.thenPrice() == null
				? MovementMath.DASH : MovementMath.formatExact(row.thenPrice()) + " gp")
				+ "&nbsp; (" + MovementMath.formatDay(stampedDay(row, w, thenDay, live)) + ")", null);
		}

		cell(sb, L_HAVE, MovementMath.formatExact(row.quantity()) + "&nbsp; =&nbsp; "
			+ (row.unitPrice() == null ? MovementMath.DASH
			: MovementMath.formatExact(row.holdingValue()) + " gp"), null);

		if (!alch)
		{
			// The one coloured figure in the block, in the row's own green or red - the FULL colour, not the
			// quiet one its face uses (AL), because here it has nothing beside it to compete with.
			cell(sb, L_CHANGE + w.label(), row.hasMovement()
				? signedExact(row.deltaGp()) + " each&nbsp; " + MovementMath.formatPct(row.deltaPct(), row.deltaGp())
				: MovementMath.DASH, row.hasMovement() ? textChangeColor(row) : null);
		}
		sb.append("</table>");

		final List<String> notes = new ArrayList<>(4);
		addNote(notes, alch ? ALCH_NOTE : partsLine(row));
		addNote(notes, view.countInventory() ? splitLine(row) : "");
		addNote(notes, live ? liveRefusalLine(row) : "");
		addNote(notes, live ? tradedNote(row) : "");
		for (int i = 0; i < notes.size(); i++)
		{
			note(sb, notes.get(i), i == 0);
		}
		return sb.append("</div></html>").toString();
	}

	/** One labelled line; {@code colour} paints the value, null leaves it the block's own grey. */
	private static void cell(StringBuilder sb, String label, String value, @Nullable Color colour)
	{
		sb.append("<tr><td>").append(Widgets.escapeHtml(label)).append("&nbsp;&nbsp;</td><td>");
		if (colour != null)
		{
			sb.append("<font color='#").append(String.format("%06X", colour.getRGB() & 0xFFFFFF)).append("'>")
				.append(value).append("</font>");
		}
		else
		{
			sb.append(value);
		}
		sb.append("</td></tr>");
	}

	/** Adds a note if it says anything at all. */
	private static void addNote(List<String> notes, String text)
	{
		if (!text.isEmpty())
		{
			notes.add(text);
		}
	}

	/**
	 * One note under the table.
	 *
	 * <p>The FIRST takes no break of its own: {@code </table>} has already ended the line, and a {@code <br>} on
	 * top of that opened a blank line the width of the cell between the figures and their note.
	 */
	private static void note(StringBuilder sb, String text, boolean first)
	{
		sb.append(first ? "" : "<br>").append(Widgets.escapeHtml(text));
	}

	/**
	 * What survives of the live line (AK): how many traded yesterday, which is what says whether a live price
	 * can be trusted. The buy and sell sides went with the spread - see {@link #detail}.
	 */
	private static String tradedNote(MovementRow row)
	{
		final MovementRow.LiveFacts facts = row.liveFacts();
		if (!row.isLive() || facts == null || facts.volumeYesterday() <= 0L)
		{
			return "";
		}
		return "Live price, " + MovementMath.formatExact(facts.volumeYesterday()) + " traded yesterday";
	}

	/**
	 * Opens or closes the cell (AI): the detail block appears under the face, and the row asks its column for
	 * the height that now needs.
	 *
	 * <p>The height is MEASURED rather than guessed, because the text is a different number of lines on every
	 * row - an alch row has three, a live parts row with a split line has eight - and a guessed constant would
	 * clip the long ones and leave a gap under the short ones. {@code DynamicGridLayout}, which
	 * {@code Widgets.column} gives the rows column, hands every child its preferred height, so re-pinning this
	 * row and revalidating the column is all that moving the rows below it takes.
	 *
	 * <p><b>The first opening also WRITES the block</b> (addendum AS), and it has to happen here and in this
	 * order: the text first, then the measurement. Measured first, an unwritten block asks only for the air above
	 * it, so the cell would open a few px tall with the whole description clipped away. Written here rather than
	 * in the click handler because the constructor calls this too: a row rebuilt from a seam that says it is open
	 * must come back open WITH its text.
	 */
	private void applyExpansion()
	{
		final boolean open = expanded();
		if (open && !detailBuilt)
		{
			// AK's rule, which is why this cannot run before the face is built: the name is repeated above the
			// table only when the face had to CUT it, and that is read off the label the face drew.
			detailLabel.setText(detail(row, window, thenDay, view, !row.name().equals(nameLabel.getText())));
			detailBuilt = true;
		}
		detailLabel.setVisible(open);
		Widgets.fixed(this, ROW_WIDTH, open ? ROW_HEIGHT + detailHeight() : ROW_HEIGHT);
		revalidate();
		repaint();
		final Container parent = getParent();
		if (parent != null)
		{
			parent.revalidate();
			parent.repaint();
		}
	}

	/**
	 * What the detail block asks for at the cell's width, in px, including the air above it. Only worth asking
	 * once the block is written (addendum AS): an unwritten one answers the air alone.
	 */
	private int detailHeight()
	{
		return detailLabel.getPreferredSize().height;
	}

	/** True while this row is open, its detail block showing under the face (AI); the seam's answer, read afresh. */
	public boolean expanded()
	{
		return expansion.isExpanded(row.id());
	}

	/**
	 * The click (AG, rebuilt by AI): the cell open, or shut again.
	 *
	 * <p>Until AI this swapped one tooltip for another and had to hand {@link javax.swing.ToolTipManager} a
	 * synthetic move to make Swing reconsider what was under the pointer. There is no tooltip to reconsider
	 * now - the description is a block inside the cell - so the click simply records the new state and lets
	 * {@link #applyExpansion()} resize the row, writing the block's text first if this is its first opening
	 * (addendum AS).
	 *
	 * @param source where the click landed; kept because every child of the row reports the click, and a
	 *               future affordance would want to know which part of the cell was pressed
	 * @param at     the click itself
	 */
	void toggleExpanded(JComponent source, MouseEvent at)
	{
		expansion.setExpanded(row.id(), !expanded());
		applyExpansion();
	}

	/** A row's own state, for every caller that hands over no {@link Expansion} - see that interface. */
	private static final class OwnExpansion implements Expansion
	{
		private boolean expanded;

		@Override
		public boolean isExpanded(int itemId)
		{
			return expanded;
		}

		@Override
		public void setExpanded(int itemId, boolean expanded)
		{
			this.expanded = expanded;
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
	 * Line 3, the ITEM line: the working behind line 2's stack value on the left - "7 x 4,618" - and what ONE
	 * item moved on the right, in the same {@link #GP_COLUMN} the stack's figure above it stands in.
	 *
	 * <p>It repeats the SHAPE of line 2 on every row, so the list has one format throughout and the two gp
	 * figures are always in one column. That is what the empty {@link #PCT_COLUMN} spacer on the far right is
	 * for: without it {@link BorderLayout} would push the gp figure out to the edge of the block and it would no
	 * longer sit under the stack's.
	 *
	 * <p>Neither label is kept in a field, because nothing reads line 3 after it is built - it never changes,
	 * and a row is thrown away and rebuilt on every publish.
	 *
	 * @param row the row being drawn; a stack of ONE prints its working but no gp figure (Q4.7)
	 */
	private static JPanel itemLine(MovementRow row)
	{
		final JLabel workingLabel = Widgets.label("", SMALL_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		// Fitted, like line 2's price, since addendum AP gave the picture back its 4 px: the widest working a
		// real bank makes ("9,999 x 9,999", 66 px) now fills the room the two columns leave EXACTLY, and an
		// unfitted WEST label one character wider would be painted under the gp figure rather than cut. The
		// hover setFitted hangs on a cut label is taken off again by clearHovers(), with every other one.
		Widgets.setFitted(workingLabel,
			MovementMath.formatGp(row.quantity()) + " " + TIMES + " " + unitText(row),
			TEXT_WIDTH - GP_COLUMN - PCT_COLUMN);
		// Q4.7: a stack of one IS the item, so its move is already printed on the line above. The working still
		// shows ("1 x 10.7k", so every row reads the same way), but the figure beside it does not repeat itself
		// - the user, on a render that did: "if there's only 1 item then only show the Total rows gp move".
		final JLabel itemGpLabel = Widgets.label(row.quantity() > 1 ? itemGp(row) : "",
			GP_FONT, quietChangeColor(row));
		itemGpLabel.setHorizontalAlignment(SwingConstants.RIGHT);
		itemGpLabel.setBorder(new EmptyBorder(0, 0, 0, FIGURE_GAP));
		Widgets.fixed(itemGpLabel, GP_COLUMN, itemGpLabel.getPreferredSize().height);
		final JPanel figures = transparent(new BorderLayout(0, 0));
		figures.add(itemGpLabel, BorderLayout.WEST);
		// 1 px tall: it is holding a width open, and a taller spacer would fight line 3's own height.
		figures.add(Widgets.fixed(transparent(new BorderLayout(0, 0)), PCT_COLUMN, 1), BorderLayout.EAST);
		final JPanel line = transparent(new BorderLayout(0, 0));
		line.add(workingLabel, BorderLayout.WEST);
		line.add(figures, BorderLayout.EAST);
		return line;
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

	/**
	 * What line 2 opens with: the whole holding's value in stack style ("32.3k"), or {@link MovementMath#DASH}
	 * without a price.
	 *
	 * <p>The dash is asked of the UNIT price and not of the holding, because {@link MovementRow#holdingValue()}
	 * is 0 on an unpriced row and a "0" there would claim the stack is worthless rather than unpriced.
	 */
	private static String stackText(MovementRow row)
	{
		return row.unitPrice() == null ? MovementMath.DASH : MovementMath.formatGp(row.holdingValue());
	}

	/**
	 * The right half of line 3's working: what ONE of them costs, in the same stack style as the stack's value
	 * above it ("32.3k") so the two figures on the row are read on the same scale. Same dash rule as
	 * {@link #stackText}.
	 */
	private static String unitText(MovementRow row)
	{
		return row.unitPrice() == null ? MovementMath.DASH : MovementMath.formatGp(row.unitPrice());
	}

	/**
	 * A signed gp move narrow enough for {@link #GP_COLUMN} - and that is the whole reason it exists.
	 *
	 * <p><b>It is compact in the thousands where the rest of the panel is not:</b> "+3.4k" where the sidebar
	 * printed "+3,432". {@link MovementMath#formatDelta} keeps four digits and a comma between 1,000 and 9,999,
	 * which wants about 48 px at {@link #GP_SIZE} - and 48 plus a {@link #PCT_COLUMN} does not fit in
	 * {@link #TEXT_WIDTH} beside a price. So this is a deliberate trade: ONE step of precision on the face,
	 * bought to keep the percentage at the size the user asked for. It is a real loss and it is the only
	 * rounding on the row that the exact figure does not appear anywhere beside - the block the cell opens is
	 * where "+3,432" still lives.
	 *
	 * <p>Nothing else rounds: under 1,000 and at 10,000 and up, {@link MovementMath#formatGp} already fits.
	 * Tenths are rounded half-up on the absolute value, so the sign never decides which way a figure goes.
	 */
	private static String signedGp(long d)
	{
		final long a = Math.abs(d);
		final String sign = d > 0 ? "+" : d < 0 ? "-" : "";
		if (a >= 1000L && a < 10_000L)
		{
			final long tenths = (a + 50L) / 100L;
			return sign + (tenths / 10L) + "." + (tenths % 10L) + "k";
		}
		return sign + MovementMath.formatGp(a);
	}

	/**
	 * Line 2's gp figure: what the whole HOLDING moved. "" on a row with nothing to say - no baseline, an
	 * untradeable one, or a price that did not move at all - because the percentage beside it is already
	 * printing the dash, the tag or "0.0%", and a row that says "0" three times says nothing three times.
	 *
	 * <p>That blank-zero rule came from {@code gpText}, the figure addendum N drew here, and outlived it
	 * (addendum N, addendum AL, addendum AO): it costs the column nothing, because {@link #GP_COLUMN} is a fixed
	 * box that an empty label holds open exactly as a filled one does. A flat stack is flat per item too
	 * ({@code holdingDeltaGp} is {@code deltaGp} times a quantity of at least one), so both lines blank together
	 * and the item's figure is never left standing alone under a gap.
	 */
	private static String stackGp(MovementRow row)
	{
		final long stack = row.hasMovement() ? row.holdingDeltaGp() : 0L;
		return stack == 0L ? "" : signedGp(stack);
	}

	/**
	 * Line 3's gp figure: what ONE item moved, the per-item reading the user asked to stop having to work out.
	 * "" on a row with no movement, and on a row whose move is not known per item.
	 *
	 * <p>A stack of one prints nothing here, but that rule is the CALLER's ({@link #itemLine}, Q4.7) and not
	 * this method's: the figure itself is perfectly well defined at a quantity of one, it is just the same
	 * number as the line above.
	 */
	private static String itemGp(MovementRow row)
	{
		final Long gp = row.hasMovement() ? row.deltaGp() : null;
		return gp == null || gp == 0L ? "" : signedGp(gp);
	}

	/*
	 * DELETED by addendum AO, with the config key that was their whole reason to exist:
	 *
	 *   priceText(row) / priceText(row, options) - the price figure line 2 opened with before the Q4 format,
	 *   the price of ONE or the value of the whole stack depending on holdingOnRows;
	 *   gpText(row) / gpText(row, options)       - the gp figure beside the percentage, the same choice again;
	 *   quantityText(row)                        - the "x12" stack tag line 2 carried before the Q4 format;
	 *   holding(options)                         - the predicate the two pairs asked which reading to answer.
	 *
	 * The Q4 format stopped drawing every one of them: the face prints the stack on line 2 (stackText, stackGp)
	 * and one item on line 3 (unitText, itemGp), so it makes no choice and needs no tag. They were kept after
	 * that only because holdingOnRows was a stored key that nobody had retired, and AO1 retires it - so what
	 * they expressed no longer exists anywhere in the plugin, and keeping them would leave five public methods
	 * describing a switch a reader cannot find.
	 */

	/**
	 * The percentage ("+1.8%", "-0.0%"), or one dash when the row has no baseline - the outermost figure on
	 * line 2 and the row's boldest. Signed by the gp figure and not by itself (L2), so a fall too small to
	 * survive truncation still shows its minus.
	 *
	 * <p>Addendum N moved the gp half of the old "+12.3k +0.8%" out of here into its own label, so the two
	 * figures are sized separately - {@link #PCT_SIZE} bold against {@link #GP_SIZE} plain, a gap the Q4 format
	 * widened - and since addendum AL they no longer share a colour either
	 * ({@link #quietChangeColor(MovementRow)}).
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
	 * The one line a CARRIED row's tooltip adds under its "Holding:" line (addendum Y, line Y3;
	 * {@code docs/bank-price-movement-addendum-Y-2026-09-13.md}): "3 in bank, 1 in inventory, 1 worn" - where the
	 * quantity on the line above it actually is.
	 *
	 * <p>It exists because addendum Y merges the three containers into ONE row: an item held in the bank and worn
	 * is a single card with the quantities summed, which is the row a reader wants and also a row whose
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
	 * The gp figure's colour since addendum AL: {@link #textChangeColor} pushed toward the row's background, so
	 * two figures on one line stop competing. Same sign rule, same source, so it can never disagree with the
	 * percentage beside it or with the rail.
	 */
	public static Color quietChangeColor(MovementRow row)
	{
		return Widgets.move(signum(row), Widgets.Kind.QUIET);
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
	 * have. It never varied with the view switches' choice of reading either - it carries the unit price AND
	 * the holding, which is why the switch that chose between them (Q6) could be deleted outright by addendum
	 * AO without this text changing a character.
	 *
	 * @param thenDay the baseline table's UTC day; null stamps {@link MovementMath#DASH}
	 */
	public static String tooltip(MovementRow row, @Nullable MovementWindow window, @Nullable LocalDate thenDay)
	{
		return tooltip(row, window, thenDay, ViewOptions.DEFAULT);
	}

	/**
	 * Which rows are showing the long hover, remembered somewhere that OUTLIVES a row (addendum AG).
	 *
	 * <p>It has to live outside, because a {@link MovementRowPanel} is not long-lived: every publish -
	 * a refresh, a bank opening, the half-hourly recheck - runs {@code rowsColumn.removeAll()} and builds the
	 * page again from new instances. A flag held in the row would therefore clear itself while the user was
	 * reading, with nothing on screen to explain why. Keyed by ITEM ID rather than by list position so it
	 * survives a re-sort and a change of price band as well.
	 *
	 * <p>The panel owns the one instance; a test can hand over its own and read what a click recorded.
	 */
	public interface Expansion
	{
		/** True while this item's row should carry the long hover. */
		boolean isExpanded(int itemId);

		/** Records a click. */
		void setExpanded(int itemId, boolean expanded);
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
	 * <p>Since addendum AS a row writes this only when {@link #tooltipHtml()} is first asked for it - which only
	 * the tests do - and every call is counted ({@link #tooltipBuilds()}), which is how they prove that a page of
	 * rows writes none.
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
		TOOLTIP_BUILDS.incrementAndGet();
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

	/**
	 * The long description ({@link #tooltip(MovementRow, MovementWindow, LocalDate, ViewOptions)}) of this row,
	 * written on the first call and kept (addendum AS). Nothing in the sidebar draws it - the open cell has shown
	 * {@link #detail} since addendum AK - so only the tests ask, and a row nobody asks writes none. EDT only, like
	 * the rest of the row; a call from another thread could at worst write it twice, and a String is safe to share.
	 */
	String tooltipHtml()
	{
		if (tooltip == null)
		{
			tooltip = tooltip(row, window, thenDay, view);
		}
		return tooltip;
	}

	/** {@link #DETAIL_BUILDS}: how many open blocks {@link #detail} has written in this JVM, for the tests. */
	static long detailBuilds()
	{
		return DETAIL_BUILDS.get();
	}

	/** {@link #TOOLTIP_BUILDS}: how many long descriptions the four-argument {@link #tooltip} has written. */
	static long tooltipBuilds()
	{
		return TOOLTIP_BUILDS.get();
	}

	String nameText()
	{
		return nameLabel.getText();
	}

	/** Line 2's opening figure as this row DREW it: the stack's value, fitted ({@link #stackText}). */
	String priceText()
	{
		return priceLabel.getText();
	}

	/** The percentage, one dash, or the {@value #ALCH_TAG} tag on an untradeable row (Q5). */
	String changeText()
	{
		return changeLabel.getText();
	}

	/**
	 * Line 2's gp figure as this row DREW it: what the whole STACK moved ({@link #stackGp}), compact in the
	 * thousands, or "" on a row with no movement - the dash beside it is already saying so. Line 3's figure is
	 * not reachable from here; nothing keeps it (see {@link #itemLine}).
	 */
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
