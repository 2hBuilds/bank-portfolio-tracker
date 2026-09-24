package com.bankpricemovement;

import com.bankpricemovement.PriceService.Status;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.PluginErrorPanel;
import net.runelite.client.util.AsyncBufferedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The 2h Bank Portfolio Tracker sidebar (contract C28-C33, presentation per addendum N's "Ticker" look - sections 2
 * and 4 of {@code docs/bank-price-movement-addendum-N-2026-09-09.md} - which addendum O made the ONLY look,
 * {@code docs/bank-price-movement-addendum-O-2026-09-09.md} line O1): a pinned header over a scrolling list
 * of {@link MovementRowPanel}s, one per bank item the {@link PriceService} publishes.
 *
 * <pre>
 * +# Bank value                        Refresh +   hero card: caption row with the "Refresh" text link
 * |# 275m                                    (*)|   the bank total, 28 bold white          [show / hide, O3]
 * |#                                            |   ...with the options gear at its right end (Q1)
 * |# v -950k   -0.3%                           |   triangle + gp move + percent, 18 bold  [show / hide, O3]
 * |#   1d    7d    30d    90d   180d           |   the window strip, the lit one bold orange, underlined
 * |# 1d vs 08 Sep - bank 09:00                 |   the provenance footnote; the LIVE day while live   [U3]
 * |# Item prices update every 24hrs            |   the update line, its own tooltip saying why      [S1, S2]
 * |#   ...or "Live prices on - thin items daily"|  ...while the live switch is on                    [T5]
 * +--------------------------------------------+
 * Percent change v                 All items v   control row: the sort column and its arrow, the band button
 * ---------------------------------------------
 * [ All | 100k+ | 1m+ | 10m+ ]  [min gp] [max gp] x  the price fold, while open
 * Refreshed 12 s ago - wait                      the problem row, while there is one
 * </pre>
 *
 * <p><b>The header</b> is a {@code DynamicGridLayout} column whose rows are ADDED and REMOVED, never hidden
 * (playbook 7.5): the hero card, the control row, the fold while open and the problem row while there is one
 * - and with no bank loaded (the LOGIN and NO_BANK cards) it is emptied, because there is nothing to value or
 * order. The four sort COLUMNS ({@link SortMode}) live in one {@link JPopupMenu}; the price fold carries
 * its presets; the "Show &lt;remainder&gt; more" row sits under the rows; the EMPTY card offers "Clear price
 * range" when a band hid everything.
 *
 * <p><b>The hero card's three figures show or hide</b> (O2-O4). A {@link HeroVisibility} says which of the
 * bank TOTAL, its gp MOVE and its PERCENTAGE are drawn. The caption row, the chips, the footnote and the
 * update line (S1) never hide; with the total off its 28 px line is REMOVED from the card and the card
 * shrinks; with the gp off the move line drops the gp figure, with the percent off it drops the percentage,
 * and with both off the move line goes and the triangle with it. The coloured left edge stays - it is a
 * direction hint, not a figure - and the card's hover is the exact bank value alone (addendum AF; with everything
 * hidden it carries the status sentence alone, which is not a figure, so no exact gp is on screen anywhere).
 * The initial choice comes from {@link Prefs#loadHero} (the plugin's three config items); the plugin forwards
 * {@code ConfigChanged} for the three keys to
 * {@link #applyHeroVisibility}, which repaints and saves nothing; and the card's right-click menu carries three
 * check items that go through {@link #setHeroVisibility}, which repaints AND writes {@link Prefs#saveHero} so
 * the STORED config follows - the same round trip the filter widgets make.
 *
 * <p><b>The gear, and the menu behind it</b> (addendum Q, lines Q1-Q2;
 * {@code docs/bank-price-movement-addendum-Q-2026-09-11.md}). A 12 px gear drawn in code
 * ({@link Widgets#gearIcon}) sits at the right end of the total's line, directly under the "Refresh" link, and
 * opens the panel's one settings menu on a LEFT click: Refresh, then the card's three show / hide items, then
 * the four view check items of {@link ViewOptions} - use live prices (addendum T, line T1), include coins and
 * platinum tokens, include untradeable items, include inventory and worn gear (addendum Y, line Y1;
 * {@code docs/bank-price-movement-addendum-Y-2026-09-13.md}), every one of them in the
 * plain words of Y4, with AH's drawn hover-text switch further down. "Show stack value on rows" was the fifth
 * of them until addendum AO, which DELETED the key behind it: addendum AN gave the row both readings at once -
 * the stack on line 2, one item on line 3 - so the switch chose nothing a reader could see.
 * The card's right-click menu is GONE with it: the same menu behind an invisible
 * gesture was the only way to reach any of this, and a settings menu a reader cannot see is a setting they do
 * not have. The gear is drawn whatever the three hero switches say, which is why the total's LINE stays in the
 * card even when the total does not.
 *
 * <p><b>The price presets are edited at the foot of that menu</b> (addendum Z, lines Z1-Z3;
 * {@code docs/bank-price-movement-addendum-Z-2026-09-13.md}): three boxes under a "Preset price ranges"
 * caption (line Z7, renamed by addendum AB line AB3), with
 * "Reset to default" beneath them, holding the three quick bands the price fold's chips offer. They are the one
 * thing in the menu that is typed into rather than ticked, and the one that edits a control elsewhere on the
 * panel - a bank whose interesting end is 10m+ has three bands ("100k+", "1m+", "10m+") that say nothing about
 * it, and the fold itself has no room for a setting of its own. They are carried by {@link BandPresets}, seeded
 * from {@link Prefs#loadPresets}, written back by {@link Prefs#savePresets} and never touch the reader's own
 * Min / Max band.
 *
 * <p><b>And the menu opens, and closes, on the gear</b> (addendum AB, lines AB1-AB2;
 * {@code docs/bank-price-movement-addendum-AB-2026-09-13.md}). A press on the gear while the menu stands closes
 * it and opens nothing: the popup is already gone by the time the gear's listener runs (RuneLite's popups are
 * heavy-weight and {@code MouseGrabber} cancels them on any outside press), so the panel remembers WHEN it went
 * ({@link #GEAR_REOPEN_GUARD_MILLIS}) instead of asking whether it is open. Under "Reset to default" the menu's
 * last row carries one small "OK" button at its right end, which commits the boxes and takes the menu down -
 * the only way out of it that is drawn rather than guessed.
 *
 * <p><b>And the fold they fill stands OPEN</b> (addendum AA, line AA1;
 * {@code docs/bank-price-movement-addendum-AA-2026-09-13.md}). The user asked for it in one sentence - "can you
 * by default have 'All items' expanded too so the user can see the quick price presets on the main tab?
 * currently it starts minimized" - and the reason is the presets themselves: a setting a reader can edit in the
 * gear menu but never see on the panel is a setting they will not know they have. So the fold is open on a
 * fresh install ({@link Prefs#loadFoldOpen}, null reading as open), the band button's press
 * ({@link #pressFold}) writes what it left behind, and the settings page's own change arrives at
 * {@link #setFoldOpen}. Nothing about the fold's CONTENTS moved: the chips, the two fields, their red rule and
 * the band button's text are addendum Z's, to the pixel.
 *
 * <p><b>The view switches are not the card's.</b> {@link HeroVisibility} says what is PAINTED and costs the
 * service nothing; {@link ViewOptions} says what the figures MEAN - which price series an actively traded stack
 * is read from (T1), whether cash is in the bank value, whether
 * untradeable stacks are listed at their alch value, whether what the player is carrying and wearing is counted
 * and listed at all (Y1), whether the sidebar's data hovers are shown (AH) - so the plugin
 * hands the same value to the service ({@code PriceService.setOptions}) and to {@link #applyOptions} here. The
 * gear menu writes through {@link Prefs#saveOptions}, exactly as its three card items write through
 * {@link Prefs#saveHero}, and the settings page's own change comes back through {@link #applyOptions}.
 *
 * <p><b>What "the config follows" does not mean.</b> RuneLite builds a {@code ConfigPanel} once and subscribes
 * it to {@code ExternalPluginsChanged} and {@code ProfileChanged} only ({@code ConfigPanel.java:835-850}), so a
 * settings page left open beside the sidebar does NOT repaint when this panel writes a value - it shows the new
 * one the next time it is entered from the plugin list. That holds for every value the sidebar writes: the three
 * hero switches and D13's filter alike. The other direction is live, because the plugin forwards
 * {@code ConfigChanged} straight into the panel.
 *
 * <p><b>Wiring for the plugin.</b> No {@code @Inject}: construct it with explicit dependencies from
 * {@code startUp} (RuneLite's PluginManager runs startUp / shutDown on the EDT and asserts so), hand it to a
 * {@code NavigationButton} and, in {@code shutDown}, {@code removeNavigation} then {@link #stop()}.
 *
 * <p><b>One filter, three owners.</b> The user's five choices (gp band, sort, direction, window) live in a
 * {@link RowFilter}. A click here builds the next filter from the current one, {@link Prefs#save saves} it
 * (the plugin's config) and hands it to {@link PriceService#setFilter}; the config panel changing the same
 * setting comes back through {@link #applyFilter}, which only repaints the widgets - the {@code updating}
 * guard keeps that from saving again, so the config and the sidebar are the same switch without a loop
 * (design D13). The hero switches are NOT part of the filter: they say how the card is painted, not what the
 * list contains.
 *
 * <p><b>The list.</b> The service's listener fires on the EDT (contract C20) with the filtered, sorted rows
 * and a {@link Status}. Rows are rebuilt only when the row list, the window, the baseline day or the sort
 * column changed - the last three are one value, {@link ListContext}, which also answers whether the reader
 * ASKED for the new list; a status-only publish touches the hero, the control row and the problem row alone (C30),
 * so the list does not flicker every five minutes. Rows are built one page of {@link #ROWS_PER_PAGE} at a
 * time (C32), and a rebuild that only re-states the SAME list - a deposit, a withdrawal, new prices, a new
 * baseline day - keeps the pages the reader opened and the place they had scrolled to; only a list the reader
 * ASKED for - a new window, ordering, direction or gp band - starts again at the top of page one (B107, see
 * {@link #rebuildRows}).
 *
 * <p><b>The bank hold</b> (addendum AS; {@code docs/handoff/plan-AS-bank-hold-2026-09-21.md} sections 1, 2.3 and 7).
 * The user's goal in one sentence: "when people just want to gear up quick and leave, the plugin should not add lag
 * to them." So while the bank interface is open the list is not rebuilt. The plugin says what it knows through
 * {@link #setBankHold} - whether the bank is open, whether the bank on screen is out of date, and two counters -
 * and this panel treats "the bank is open" exactly as it has always treated "the sidebar is hidden": {@link #onRows}
 * stores the publish and builds nothing, and the stored one is replayed when the hold ends. Two things lift it
 * early, because a hold that ignored them would be a sidebar ignoring its reader: the reader's own act - the Refresh
 * link, a window, a column, a band, a view switch ({@link #liftBankHold}) - and a publish carrying a bank the
 * sidebar has not drawn, which is a read and not a re-statement ({@link #carriesNewBank}). While a change is owed
 * and the reader can see the link, a thin green ring breathes round "Refresh" - 3 s in, 3 s out, painted by the card
 * under the word ({@link HeroCard}, {@link #breath}). A click on the link refreshes EVERYTHING, whatever the bank is
 * doing (AS8, the user on the AS7 build: "manually clicking the refresh button should refresh everything for the
 * user"): with the bank open the plugin's local re-read ({@link #setBankRefresh}) redraws the items at once and the
 * price re-check follows in the background, its 30 s cooldown kept to the prices; with the bank shut it is the price
 * re-check, which re-reads what the player carries as well ({@link #refreshNow}). The gear's "Refresh prices now"
 * stays the price re-check alone ({@link #refreshPricesNow}), and the link's hover is one sentence in every state
 * ({@link #REFRESH_TIP}).
 *
 * <p><b>Type and colour.</b> Every label here sets its font through {@link Widgets#sans} /
 * {@link Widgets#sansBold} (RuneLiteLAF installs the 16 px bitmap face as the default, and the type scale is
 * 28 / 18 / 14 / 13 / 12 / 11 of one family), and every colour is a {@link ColorScheme} constant or its
 * {@code darker()} (N section 2).
 *
 * <p><b>Threading.</b> Everything here is EDT-only. The listener is called through the service's
 * {@code edt} consumer already; a call arriving on any other thread is re-posted with
 * {@code SwingUtilities.invokeLater}. {@code ItemManager.getImage} is called from the EDT while building a
 * page (C31) - it answers a cached, possibly still-blank {@link AsyncBufferedImage} that fills itself in
 * later ({@code ItemManager.java:527-529}).
 */
public class BankPriceMovementPanel extends PluginPanel
{
	/**
	 * Where the filter and the hero card's three show / hide switches are kept between sessions - the plugin's
	 * config, or a memory in the tests (contract C28; the hero half is addendum O, line O2).
	 *
	 * <p>The two hero methods have defaults so a caller with nothing to remember - the renderer, a throwaway
	 * test seam - need not care: every figure is then shown and nothing is written. The plugin MUST override
	 * both over {@code ConfigManager}, so a right-click toggle on the card ({@link #setHeroVisibility}) is
	 * written where the config panel READS it - this panel follows on the {@code ConfigChanged} that write posts,
	 * and the settings page shows it the next time it is opened (O4; see the class javadoc) - without them the
	 * card would repaint and forget the choice on the next launch.
	 */
	public interface Prefs
	{
		/** The saved filter; null means "fresh install" and reads as {@link RowFilter#DEFAULT}. */
		@Nullable
		RowFilter load();

		void save(RowFilter filter);

		/**
		 * The saved switches (the config's {@code showBankValue} / {@code showBankMoveGp} /
		 * {@code showBankMovePct}); null reads as {@link HeroVisibility#ALL} (O2).
		 */
		@Nullable
		default HeroVisibility loadHero()
		{
			return null;
		}

		/** Writes the three switches the card's right-click menu chose, so the stored config follows (O4). */
		default void saveHero(HeroVisibility visibility)
		{
		}

		/**
		 * The saved view switches - the config's {@code countCash} / {@code countUntradeables} (addendum Q,
		 * line Q3), {@code livePrices} (addendum T, line T1), {@code countInventory} (addendum Y, line Y1) and
		 * {@code showHoverText} (addendum AH); null reads as {@link ViewOptions#DEFAULT}.
		 *
		 * <p>Five keys, ONE value: a switch is added to {@link ViewOptions} and to the plugin's implementation of
		 * this pair, and every reader of the menu, the card and the rows follows without a new seam. A switch can
		 * leave the same way - addendum AO deleted {@code holdingOnRows}, which Q3 had added here, once addendum
		 * AN's three-line row printed both of its readings at once and left it choosing nothing.
		 *
		 * <p>Defaulted for the same reason the hero pair is: the renderer and a throwaway test seam have nothing
		 * to remember, and a plugin that overrides neither draws the sidebar exactly as it drew before addendum Q.
		 */
		@Nullable
		default ViewOptions loadOptions()
		{
			return null;
		}

		/** Writes the five switches the gear menu chose, so the stored config follows (Q2, T1, Y1, AH). */
		default void saveOptions(ViewOptions options)
		{
		}

		/**
		 * The saved quick bands - the config's {@code bandPresets}, already read off its shorthand (addendum Z,
		 * line Z1; {@code docs/bank-price-movement-addendum-Z-2026-09-13.md}).
		 *
		 * <p>Null reads as {@link BandPresets#DEFAULT}, and that is the ONLY answer a stored string which does not
		 * name three distinct positive amounts may give: {@link BandPresets#parse} answers null for such a string,
		 * so an implementation hands that null straight on and the panel opens on 100k / 1m / 10m. A config value
		 * is a thing a user can type into, so it is read as a wish and never as a promise.
		 *
		 * <p>Defaulted for the same reason the hero and option pairs are: the headless renderer and a throwaway
		 * test seam have nothing to remember, and a {@code Prefs} that overrides neither draws the fold exactly as
		 * it drew before addendum Z.
		 */
		@Nullable
		default BandPresets loadPresets()
		{
			return null;
		}

		/** Writes the three the gear menu's boxes chose, so the stored config follows (Z1, Z2). */
		default void savePresets(BandPresets presets)
		{
		}

		/**
		 * Whether the price fold stands OPEN under the control row - the config's {@code foldOpen} (addendum AA,
		 * line AA1; {@code docs/bank-price-movement-addendum-AA-2026-09-13.md}).
		 *
		 * <p>Null reads as OPEN, which is the shipped default and the answer for a fresh install: the user asked
		 * for the quick presets to be on the main tab rather than behind a gesture ("can you by default have 'All
		 * items' expanded too so the user can see the quick price presets on the main tab?"). A {@code Boolean}
		 * rather than a {@code boolean} for exactly that reason - "nothing stored" and "stored false" are different
		 * answers, and only the first may be overridden by the default.
		 *
		 * <p>Defaulted for the same reason every pair since the filter is: the headless renderer and a throwaway
		 * test seam have nothing to remember, and they get the shipped sidebar.
		 */
		@Nullable
		default Boolean loadFoldOpen()
		{
			return null;
		}

		/** Writes the state the band button's press left the fold in, so the stored config follows (AA1). */
		default void saveFoldOpen(boolean open)
		{
		}
	}

	/** Rows built per page (contract C32, design D11). */
	public static final int ROWS_PER_PAGE = 250;

	public static final String CARD_LOGIN = "LOGIN";
	public static final String CARD_NO_BANK = "NO_BANK";
	public static final String CARD_EMPTY = "EMPTY";
	public static final String CARD_LIST = "LIST";

	public static final String TITLE = "2h Bank Portfolio Tracker";
	public static final String LOGIN_TEXT = "Log in to load your bank";
	public static final String NO_BANK_TEXT = "Open your bank once to load your items";
	/** The EMPTY card's title when the gp band matched nothing (N section 3 §6). */
	public static final String EMPTY_TEXT = "Nothing in this price range";
	/** The EMPTY card's title when the bank holds no tradeable item at all. */
	public static final String NO_TRADEABLES_TEXT = "Your bank holds no tradeable items";
	/** The EMPTY card's title when there is a bank but no price for anything in it yet. */
	public static final String NO_PRICES_TEXT = "None of your items has a price yet";
	/** The EMPTY card's one-tap way out of a band that hid everything (N section 2). */
	public static final String CLEAR_BAND_TEXT = "Clear price range";
	/** The caption row's text link (N 4.4 control 4). */
	public static final String REFRESH_TEXT = "Refresh";
	/** What that link says for {@link #REFRESH_ACK_MILLIS} after a tap, so an accepted refresh answers. */
	public static final String REFRESHING_TEXT = "Refreshing...";
	/** How long the acknowledgement holds: long enough to read, short enough not to outlast the fetch. */
	static final int REFRESH_ACK_MILLIS = 1_800;
	/**
	 * What the link says when the acknowledgement expires, until the fade takes it back to {@link #REFRESH_TEXT}
	 * (addendum P, line P2). The same face and the same grey - it is still the Refresh control, saying that the
	 * tap it was given has been served.
	 */
	public static final String UP_TO_DATE_TEXT = "Up to date";
	/**
	 * How long "Up to date" stands before the link reads "Refresh" again (P2, the user's own wish: "can you get
	 * that wording to fade away after 1min tho so it keeps everything looking minimal").
	 */
	static final int UP_TO_DATE_MILLIS = 60_000;
	/**
	 * How often the ring round the Refresh link is redrawn (addendum AS, section 7.3; a ring since AS7): 25 frames a
	 * second is smooth to the eye, and each frame repaints the ring's own ~54 x 23 px of the hero card and nothing
	 * else ({@link #fireGlow}). A frame only asks for the repaint - how bright the ring is comes from the clock
	 * ({@link #glowLevel}) - so the rate sets how smooth the breath looks and never how long it takes.
	 */
	static final int GLOW_TICK_MILLIS = 40;
	/**
	 * One breath of the ring, in ms: 3 s from nothing to full brightness and 3 s from full back to nothing
	 * ({@link #breath}), round and round for as long as the ring is lit.
	 *
	 * <p>The user's own numbers. The candidates were rendered breathing once every 1.2 s, and on the one they picked,
	 * P1 "Breathing ring", they asked for it slower in so many words: "lets slow down the breathing to 3 seconds from
	 * 0 to max and then 3 seconds from max to 0 again". So the breath runs from NOTHING - the rendered P1 never went
	 * below a quarter - which also makes the ring fade in rather than appear: at the moment it lights it is dark.
	 */
	static final int GLOW_BREATH_MILLIS = 6_000;
	/**
	 * The floor of the breath: the ring never dims below this share of full. The user chose it after seeing the
	 * 0-to-full build's numbers ("okay do that, 15%") on the argument that the ring carries a STATE - the list is
	 * behind - as well as a call for attention, and a ring that goes fully dark looks, for a moment in every breath,
	 * exactly like the ordinary up-to-date link. The breath does the attention; the floor keeps the state readable at
	 * every instant. 15 % is the subtle end of the usual 15-25 %; it is one constant to tune by eye.
	 */
	static final double GLOW_FLOOR = 0.15d;
	/** The ring's width, in px: thin enough to sit in the link's 2 px top and bottom insets. */
	static final float GLOW_STROKE = 1.5f;
	/**
	 * The ring's rounding, in px: a {@link RoundRectangle2D}'s arc on the ring's centre line (the halo's two rings are
	 * rounded to stay concentric with it, {@link #paintRing}).
	 */
	static final float GLOW_CORNER = 8f;
	/**
	 * How much wider than the Refresh link the ring's box is, on its right, in px - the number the rendered
	 * candidates found (2026-09-23). The link's own box is lopsided: its inset puts {@link #ROW_GAP} px of air before
	 * the word, the hit area that inset exists for, and the word's ink ends 1 px short of the box's right edge. A ring
	 * round that box would stand 6 px from the "R" and brush the "h" - AS4's orbiting spark clipped the h's right stem
	 * - so the ring's box runs on 5 px into the card's 10 px right padding and the word sits centred in the ring, about
	 * 4.5 px of air each side. The link's own box, and so the layout, does not move by a pixel.
	 */
	static final int GLOW_RING_EXTRA_RIGHT = 5;
	/**
	 * The halo: two 1 px rings just outside the ring, the near one at this share of the ring's alpha and the far one
	 * at {@link #GLOW_HALO_FAR_ALPHA}, so the light softens into the card instead of stopping at a hard edge.
	 */
	static final double GLOW_HALO_NEAR_ALPHA = 0.45d;
	/** The halo's far ring, 1 px beyond the near one; see {@link #GLOW_HALO_NEAR_ALPHA}. */
	static final double GLOW_HALO_FAR_ALPHA = 0.20d;
	/**
	 * How far the halo reaches outside the ring's box, in px: its two 1 px rings. With the box's own width and
	 * height, the region a frame repaints ({@link #glowRegion}).
	 */
	static final int GLOW_HALO_REACH = 2;
	/**
	 * The ring's colour: the green a row prints a rise in ({@link Widgets#move}, {@link Widgets.Kind#FIGURE}), so
	 * the sidebar's one "something new" colour is the one it already uses for good news - and {@link Widgets#move}
	 * is where every such colour is chosen, so this one cannot drift from the rows'.
	 */
	static final Color GLOW_COLOUR = Widgets.move(1, Widgets.Kind.FIGURE);
	/** The gear menu's first entry - the second home of Refresh (N 3.1, O4; the gear's since Q2). */
	public static final String REFRESH_MENU_TEXT = "Refresh prices now";
	/** The gear's tooltip (Q1): one word, because the menu under it says the rest. */
	public static final String OPTIONS_TIP = "Options";
	/**
	 * How long after the gear menu closes a press on the GEAR opens nothing (addendum AB, line AB1;
	 * {@code docs/bank-price-movement-addendum-AB-2026-09-13.md}) - the width of the toggle's second half.
	 *
	 * <p>It exists because the gear cannot see its own menu. RuneLite forces every popup heavy-weight
	 * ({@code ClientUI.setupDefaults}), and {@code BasicPopupMenuUI.MouseGrabber} cancels an open popup on any
	 * press OUTSIDE it - the gear is outside - BEFORE that press reaches the gear's own mouse listener. So a
	 * second click on the gear always arrives at a listener that finds the menu already closed, and the loop the
	 * user photographed follows: "if we click the gear settings icon while its already open it closes, currently
	 * it just reopens on a loop". The panel therefore remembers WHEN the menu went away
	 * ({@link #menuClosedAtMillis}) and a press inside this window is read as the close it really was.
	 *
	 * <p>300 ms is a click and not a pause: it is longer than the interval between the grabber's cancel and the
	 * label's {@code mousePressed} (one event queue hop) and shorter than any deliberate re-open. A reader who
	 * shuts the menu by clicking the sidebar and then wants it back waits a third of a second, which is the whole
	 * cost of the rule.
	 */
	static final long GEAR_REOPEN_GUARD_MILLIS = 300L;
	/**
	 * What {@link #menuClosedAtMillis} holds while the menu has never closed - a sentinel rather than 0, so that
	 * the guard never subtracts from a stamp that is not one (a pinned clock is free to read 0).
	 */
	private static final long MENU_NEVER_CLOSED = Long.MIN_VALUE;
	/**
	 * The gear menu's second group (Q2), named as their config items are (Q3) - and since addendum Y (line Y4;
	 * {@code docs/bank-price-movement-addendum-Y-2026-09-13.md}) named in the words a reader who has never read a
	 * contract would use.
	 *
	 * <p>The user asked for that pass in one sentence - "Perhaps we should come up with more layman names for the
	 * other selections" - and the new words follow one rule: a switch that decides what is COUNTED opens with
	 * "Include", and a switch that decides what is DRAWN opens with "Show". The cash item names the thing it counts
	 * in full, as the game does.
	 *
	 * <p>Only the WORDS changed: the keys, the defaults, the order and what each switch does are untouched, and
	 * nothing drawn on the sidebar moved - these labels live in this popup and on the settings page.
	 */
	public static final String COUNT_CASH_TEXT = "Include coins and platinum tokens";
	public static final String COUNT_UNTRADEABLES_TEXT = "Include untradeable items";
	/**
	 * The group's fourth item since addendum Y (line Y1), directly after the untradeables it reads as a sibling of:
	 * whether what the player is CARRYING - the inventory and the worn gear - is counted in the bank value and
	 * listed beside the bank's own stacks.
	 *
	 * <p>It sits with the two "Include" switches because it answers the same question they do - what is in the sum -
	 * and it is the LAST of the view check items since addendum AO deleted the row switch that used to follow it.
	 * Its own words are the user's: "an option that is on by default (Include inventory and gear equipped)".
	 */
	public static final String COUNT_INVENTORY_TEXT = "Include inventory and worn gear";
	/**
	 * The group's FIRST item since addendum T (line T1;
	 * {@code docs/bank-price-movement-addendum-T-2026-09-12.md}), named as its config item is: which PRICE SERIES
	 * the figures are read from. It leads the group because it is the switch the other three qualify - what a stack
	 * is worth is answered before whether it is counted - and because it is the one a reader comes to the menu for
	 * after seeing the card's line say "Live prices on".
	 *
	 * <p>Y4 gave it a verb ("Use live prices"): a bare noun phrase beside four sentences read as a heading for them
	 * rather than as the switch it is.
	 */
	public static final String LIVE_PRICES_TEXT = "Use live prices";
	/** Their tooltips - the config items' own descriptions (Q3, T1, Y1). */
	public static final String LIVE_PRICES_TIP = "Actively traded items use the wiki's live traded prices for every "
		+ "figure; thin items keep the daily guide price";
	public static final String COUNT_CASH_TIP = "Coins and platinum tokens (1,000 gp each) count in the bank value";
	public static final String COUNT_UNTRADEABLES_TIP = "List untradeable stacks at their tradeable parts' value, or else their High Alchemy value,"
		+ " and count them in the bank value";
	/**
	 * The new item's hover (Y1). It says WHEN, because the answer is not "always": the two containers are read with
	 * the bank and on Refresh and at no other moment (Y2), so a reader who drops something and watches the list sit
	 * still is told why before they wonder. It names the CLOSE since addendum AS, which holds a change seen with the
	 * bank open and reads it once, at the close or at a Refresh click - word for word the config item's description,
	 * whose javadoc says what the one sentence leaves out.
	 */
	public static final String COUNT_INVENTORY_TIP = "Items in your inventory and worn gear count in the bank value "
		+ "and are listed with the bank's stacks. They are read when you close the bank or press Refresh.";
	/**
	 * The caption of the gear menu's last row (addendum Z, line Z2;
	 * {@code docs/bank-price-movement-addendum-Z-2026-09-13.md}): the three quick bands the price fold offers,
	 * in three boxes a reader can type into.
	 *
	 * <p>It is a LABEL and not a check item because the thing under it is not a switch - the user asked to "see
	 * and change the 3 default filter options for price mins", and a bank whose interesting end is 10m+ has three
	 * bands that say nothing about it. The words are the config item's own (Z1), so the settings page and the menu
	 * name the same setting.
	 *
	 * <p><b>The words are the user's own, twice over.</b> Line Z7, after the first hand look at the boxes, asked
	 * for what the boxes DO rather than what they are called in the config ("also call it 'Quick price filter
	 * presets' if that fits nicely"); addendum AB line AB3, after the next one, asked for the same thing in fewer
	 * ("Also change the wording from 'Quick price filter presets' to 'Preset price ranges'",
	 * {@code docs/bank-price-movement-addendum-AB-2026-09-13.md}). Three words are what a caption over three boxes
	 * can carry - "price ranges" is what the boxes hold and "Preset" is what makes them one tap rather than two
	 * bounds typed by hand. It fits, as Z7's did: the sentence is shorter than "Include coins and platinum tokens"
	 * one group above, so the menu is no wider for it. The config item carries the same string (AB3) and the KEY
	 * is untouched by either rename, so a stored trio survives both.
	 */
	/**
	 * Addendum AH's item, restored by AJ: the last item in the gear menu, in the row ABOVE the bottom
	 * "[Reset to default]  [OK]" row, where the user asked for it - "it should be the row above OK".
	 */
	public static final String SHOW_HOVER_TEXT_TEXT = "Show hover text";
	/**
	 * Its own hover, which the switch hides like every other since AH3 - a reader who has turned the hovers
	 * off does not want this one explaining itself either, and the item's own words say what it does.
	 */
	public static final String SHOW_HOVER_TEXT_TIP = "Show hover text anywhere in the sidebar: the bank value "
		+ "and the controls";
	public static final String PRESETS_TEXT = "Preset price ranges";
	/** The menu item under the boxes (Z2) - 100k / 1m / 10m back in one click, in the boxes, the fold and the config. */
	public static final String RESET_PRESETS_TEXT = "Reset to default";
	/** The row's hover - the config item's own description (Z1), so both places say the same sentence. */
	public static final String PRESETS_TIP = "The three quick bands under the band button, in gp shorthand and "
		+ "smallest first - for example 1m, 10m, 100m.";
	/** The reset item's hover: the three amounts it puts back, named. */
	public static final String RESET_PRESETS_TIP = "Put the 100k, 1m and 10m bands back";
	/**
	 * The button on the menu's last row (addendum AB, line AB2;
	 * {@code docs/bank-price-movement-addendum-AB-2026-09-13.md}): the way OUT of the gear menu, at the bottom
	 * right of it, under "Reset to default".
	 *
	 * <p>The user asked for "a 'save' or 'ok' button ... that will close the settings box as well", and it is
	 * <b>OK</b> and not "Save" because there is nothing here left to save when it is pressed: every switch in this
	 * menu writes itself the moment it is ticked, and the three boxes commit on Enter, on leaving a box and on the
	 * menu closing (Z2). A button labelled "Save" beside controls that have already saved asks a reader to wonder
	 * what would happen if they did not press it.
	 */
	public static final String OK_TEXT = "OK";
	/** Its hover: the two things the press does, in the order it does them (AB2). */
	public static final String OK_TIP = "Close this menu; the price ranges above are saved first";
	/**
	 * The tooltip of the refresh link (addendum S, line S2;
	 * {@code docs/bank-price-movement-addendum-S-2026-09-11.md}): what the control does, and the fact that decides
	 * whether a second tap is worth making - Jagex publishes the guide prices once a day, so a re-check almost
	 * always brings the same figures back. The 30 s cooldown is no longer named here: the link is never disabled,
	 * a refused tap is answered in the problem row (K5/L10), and the sentence a reader needs before tapping is
	 * how often the DATA moves, not how often the button may be pressed.
	 *
	 * <p><b>Reworded by AS8</b>, when a click on the link became one act everywhere ({@link #refreshNow}): it re-reads
	 * the items - the bank itself while it is open, what the player carries in every state - and re-checks the prices,
	 * so the first sentence names both halves and the second keeps S2's once-a-day fact, which is still what decides
	 * whether a second tap is worth making. And it is once more the link's ONLY hover: addendum AS's F5 gave the link a
	 * second one, "Update the list with your bank as it is now.", while a click with the bank open was the local
	 * update alone and this one described a price check that click was not making; with the click the same in every
	 * state, one sentence is true in every state, and a hover that switched would describe a difference that is gone.
	 */
	public static final String REFRESH_TIP = "Re-read your items and re-check the prices. Jagex publishes guide prices "
		+ "once a day.";
	/**
	 * The three check items of the gear menu's first group (O4), named as the config items are (O2) - and in the
	 * plainer words of Y4: the card's caption already says "Bank value" one line above the figures the second and
	 * third draw, so each of them names the CHANGE, which is what a reader calls it.
	 */
	public static final String SHOW_VALUE_TEXT = "Show bank value";
	public static final String SHOW_GP_TEXT = "Show change in gp";
	public static final String SHOW_PCT_TEXT = "Show change in %";
	/** Their tooltips - the config items' own descriptions (O2). */
	public static final String SHOW_VALUE_TIP = "Show the whole-bank total on the card";
	public static final String SHOW_GP_TIP = "Show the bank's gp change for the chosen window";
	public static final String SHOW_PCT_TIP = "Show the bank's percentage change for the chosen window";
	/**
	 * The sort word-button's whole tooltip (addendum X, line X2;
	 * {@code docs/bank-price-movement-addendum-X-2026-09-13.md}): ONE phrase, the same under every column and
	 * both directions.
	 *
	 * <p>Addendum W's hover named the ordering in force - the column, then the direction in words, then the
	 * gesture - which is sixteen sentences restating what the button's own face already prints (the label) and
	 * wears (the arrow, W2). What a reader cannot deduce is what PICKING something will do, and that is now
	 * said by the thing they are about to pick: each menu entry's own hover (X1). So the button is left with
	 * the one thing only it can say - what it opens.
	 */
	public static final String SORT_BUTTON_TIP = "Change sorting";
	/** The band button's tooltip while no band is set (N 3.3). */
	public static final String BAND_TIP = "Show only items in a price range";
	/** The hero card's caption (N 3.1, 4.2). */
	public static final String VALUE_TITLE = "Bank value";
	/** What the card's hover puts after the exact figure, so the number on its own names its unit (AF). */
	static final String GP_SUFFIX = " gp";
	/**
	 * The permanent line under the provenance footnote (addendum S, line S1;
	 * {@code docs/bank-price-movement-addendum-S-2026-09-11.md}), in the footnote's own face and grey: the one
	 * place on the panel that says the data steps ONCE A DAY.
	 *
	 * <p>The words are the user's own and are kept to the character - they asked for "some written indication
	 * that it gets data only once every 24 if the user is refreshing" and then spelled it: "line, use 'Item
	 * prices update every 24hrs' and if this is hovered then show why". So the sentence is a LINE, not a beat on
	 * the Refresh control, and the "why" is behind it ({@link #updateTooltip}).
	 *
	 * <p>It is drawn in every hero state, with every option and with all three figures hidden - a reader who
	 * cannot see a figure can still see why the one they refreshed for did not move. It replaces the note
	 * addendum P hung on the card's tooltip (P2): a sentence only a hover can reach is a sentence the reader
	 * looking for it never finds.
	 */
	public static final String UPDATE_TEXT = "Item prices update every 24hrs";
	/**
	 * What that same line reads while {@code livePrices} is on (addendum T, line T5): the sentence it replaces is
	 * then only half true, because the actively traded stacks move whenever the wiki's traded series does and it is
	 * the THIN ones that still step once a day.
	 *
	 * <p>Both halves are load-bearing, and in this order: the reader is told what changed ("Live prices on") and
	 * then what did not ("thin items daily"), which is the half that explains a row still sitting still after a
	 * Refresh. The whole of it - what "actively traded" means, and when this client last looked - is behind the same
	 * hover the guide line has ({@link #updateTooltip}).
	 */
	public static final String UPDATE_LIVE_TEXT = "Live prices on - thin items daily";
	/** The first sentence of that line's tooltip (S2): why once a day, and whose day it is. */
	static final String UPDATE_WHY = "2h Bank Portfolio Tracker uses the Grand Exchange guide price, which Jagex "
		+ "publishes once a day at a varying hour.";
	/**
	 * Its three source sentences (the price-source study, 2026-09-11): which page shows this price, why RuneLite's
	 * own hover can differ, and why a big move takes days to show. One sentence per line - Swing never wraps a tooltip.
	 */
	static final String UPDATE_SOURCE = "It is the price shown on the Grand Exchange website.<br>"
		+ "RuneLite's own item hover uses the wiki's traded price by default, which differs most on thinly traded items.<br>"
		+ "Jagex moves a guide price by at most about 5% a day, so a large move shows over several days.";
	/**
	 * Its second sentence: what re-checking does, by hand and by itself. The half-hour is
	 * {@link PriceService#TICK_MS}, which runs while the sidebar is open (design D8).
	 */
	static final String UPDATE_RECHECK = "Refresh re-checks for it, and the plugin re-checks by itself every 30 minutes.";
	/**
	 * The live line's own first sentence (addendum T, line T5): which series the moving rows are on, how often it
	 * is re-read, and - the half a reader asks about first - that a window compares live against the TRADED average
	 * of that day rather than against a guide price.
	 */
	static final String UPDATE_LIVE_WHY = "Actively traded items show the wiki's live traded price, refreshed on "
		+ "Refresh and every 30 minutes; their windows compare against that day's traded average.";
	/**
	 * Its second sentence (T5, rewritten by addendum V line V5): what "thin" means, named as the FIVE checks now
	 * measure it - fewer than {@code LIVE_MIN_VOLUME} traded yesterday, a wide buy/sell gap in today's quote or in
	 * yesterday's daily bucket (V3), or a live price more than half away from the guide or from yesterday's traded
	 * average (V4) - and what such a stack shows instead. The numbers are spelled out because the reason a given
	 * row failed is on that row's own tooltip and this is where a reader learns what those reasons mean.
	 *
	 * <p>The two new clauses are folded into the old ones rather than listed after them ("today or yesterday", "the
	 * guide or from yesterday's average"): the checks pair up two by two, and a reader wants to know what kind of
	 * thing disqualifies a row, not to count five of them.
	 */
	static final String UPDATE_LIVE_THIN = "Thin items (fewer than 100 traded yesterday, a wide buy/sell gap today "
		+ "or yesterday, or a live price more than 50 % from the guide or from yesterday's average) keep the daily "
		+ "Grand Exchange guide price.";
	/** The opening of its third sentence, before the clock; the whole sentence is absent at 0 (S2). */
	static final String UPDATE_LAST_CHECKED = "Last checked ";
	/** What separates the three parts of {@link #moveText} ("1d   +12.4m   +1.0%"), kept for the bridge (N §8). */
	static final String VALUE_SEP = "   ";

	/** The content width every header row is designed at and measured against (contract C28, N6, O6). */
	static final int W = Widgets.CONTENT_WIDTH;
	/** Each gp field's width (contract C29, N 3.5). */
	static final int FIELD_WIDTH = 90;
	/** The hero card's usable width: 213 minus the 3 px edge and the 9 + 10 px padding of {@link Widgets#card}. */
	static final int CARD_INNER = W - Widgets.EDGE_WIDTH - 9 - 10;
	/** The window strip inside the card: five cells across its 191 px, no gaps (N 4.4 control 1). */
	static final int CHIP_WIDTH = CARD_INNER / MovementWindow.values().length;
	static final int CHIP_HEIGHT = 22;
	/** The fold's four presets: 49 px each with 3 px between = 205 = 213 minus the fold's 4 + 4 padding (N 3.5). */
	static final int PRESET_WIDTH = 49;
	static final int PRESET_GAP = 3;
	static final int PRESET_HEIGHT = 24;
	/** The control row's content height (N 3.3) and the gap above it (N section 3 §3). */
	static final int CONTROL_HEIGHT = 22;
	static final int ROW_GAP = 6;
	/** The "Show n more" row (N section 3 §5). */
	static final int SHOW_MORE_HEIGHT = 26;
	/**
	 * How many bands a {@link BandPresets} holds - the fold's chips after "All", and the boxes in the gear menu
	 * (addendum Z, lines Z2 and Z3).
	 */
	static final int BANDS = 3;
	/** The fold's chips: "All" and the three presets. Their lower bounds are no longer constants (Z3). */
	static final int PRESET_COUNT = BANDS + 1;
	/** The first chip, which is not a preset at all: no lower bound, so every item (N 3.5). */
	static final String ALL_LABEL = "All";
	/**
	 * Each preset box in the gear menu (Z2): narrower than the fold's {@link #FIELD_WIDTH} gp fields, because
	 * three of them share one menu row and the longest thing a band is ever spelled as is five characters
	 * ("1.5m", "100m", "2.15b").
	 */
	static final int PRESET_FIELD_WIDTH = 56;

	/** The sidebar margin each side of the content: (225 - 213) / 2. */
	private static final int MARGIN = (PluginPanel.PANEL_WIDTH - W) / 2;
	private static final int GAP = 4;
	/** The "x" in the fold (N 3.5). */
	private static final int SMALL_ICON = 11;
	/**
	 * The sort triangle's width and the gap between a word-button's text and its icon - the two pieces of the
	 * control row's geometry that are not text, on both word-buttons. Package-private because {@code WidgetsTest}
	 * measures W1's four column labels against the room they leave (addendum W's width rule) rather than against
	 * two numbers copied out of here.
	 */
	static final int TRIANGLE_ICON = 7;
	static final int ICON_GAP = 5;
	/** Between the gp figure and the percentage on the card's move line (N 4.2). */
	private static final int MOVE_GAP = 8;

	private static final Logger log = LoggerFactory.getLogger(BankPriceMovementPanel.class);

	private final ItemManager itemManager;
	private final PriceService service;
	private final Prefs prefs;
	private final PriceService.Listener listener;

	// ---- header (built once, in buildHeader)
	private final JPanel header;
	private JPanel hero;
	private JPopupMenu heroMenu;
	private JCheckBoxMenuItem showValueItem;
	private JCheckBoxMenuItem showGpItem;
	private JCheckBoxMenuItem showPctItem;
	/** The last group's first item since addendum T (T1): the price series the figures are read from. */
	private JCheckBoxMenuItem livePricesItem;
	private JCheckBoxMenuItem countCashItem;
	private JCheckBoxMenuItem countUntradeablesItem;
	/** The group's fourth item since addendum Y (Y1), and its last since AO: the inventory and the worn gear. */
	private JCheckBoxMenuItem countInventoryItem;
	/**
	 * AH: a plain {@link JMenuItem} carrying a DRAWN box rather than a {@link JCheckBoxMenuItem}, because this
	 * switch ships OFF and RuneLite paints an unticked check item blank - which beside a label is
	 * indistinguishable from a plain command, so nothing would say it is a switch at all.
	 */
	private JMenuItem showHoverTextItem;
	/** The menu's box row since addendum Z (Z2): the caption over the three preset boxes. */
	private JPanel presetRow;
	/** Those boxes, smallest first - the same style, and the same red rule, as the fold's Min / Max fields. */
	private final Widgets.PlaceholderField[] presetFields = new Widgets.PlaceholderField[BANDS];
	/** The item under them (Z2). */
	/** AH2: "Reset to default" is a BUTTON in the bottom row beside OK, no longer an item of its own. */
	private JButton resetPresetsButton;
	/** The menu's last row since addendum AB (AB2): the glue and, at the right end of it, {@link #okButton}. */
	private JPanel okRow;
	/** The way out of the menu (AB2). */
	private JButton okButton;
	private JPanel captionRow;
	private JLabel captionLabel;
	private JLabel refreshLabel;
	/** The total's line: the figure WEST and the options gear EAST; the line stays for the gear (Q1). */
	private JPanel totalRow;
	private JLabel gearLabel;
	private JLabel totalLabel;
	private JPanel moveLine;
	private JLabel triangleLabel;
	private JLabel deltaLabel;
	private JLabel pctLabel;
	private JPanel stripHolder;
	private JLabel footnoteLabel;
	/** The card's last line: {@link #UPDATE_TEXT}, with its own tooltip rather than the card's (S1, S2). */
	private JLabel updateLabel;
	/** Every hero component the card's tooltip goes on (playbook 7.5). */
	private final List<JComponent> heroTipTargets = new ArrayList<>();
	/** The window strip inside the card. */
	private JPanel chipRow;
	private final Map<MovementWindow, JLabel> windowChips = new EnumMap<>(MovementWindow.class);
	private JPanel controlRow;
	private JLabel sortButton;
	private JLabel bandTarget;
	private JPanel fold;
	private final JLabel[] presetCells = new JLabel[PRESET_COUNT];
	private JLabel clearLabel;
	private final Widgets.PlaceholderField minField;
	private final Widgets.PlaceholderField maxField;
	private JLabel problemLabel;
	/** The sort menu that is open, if any - one at a time (the BeamPickerPopup idiom). */
	@Nullable
	private JPopupMenu sortMenu;

	// ---- centre: the four cards
	private final CardLayout cardLayout = new CardLayout();
	private final JPanel cards;
	private final JPanel loginCard;
	private final JPanel noBankCard;
	private final JPanel emptyCard;
	private final JPanel emptyColumn;
	private final PluginErrorPanel emptyMessage;
	private final JPanel clearBandRow;
	private final JButton clearBandButton;
	private final JScrollPane scroll;
	/** The scroll pane's VIEW: the list column anchored north (see {@link Widgets#north}). */
	private final JPanel listView;
	private final JPanel listColumn;
	private final JPanel rowsColumn;
	private final JPanel showMoreRow;
	private final JLabel showMoreLabel;

	// ---- state (EDT)
	private RowFilter filter;
	private HeroVisibility heroVisibility;

	/**
	 * Every component in this panel whose hover the addendum-AH switch governs, with the text it carries while
	 * the switch is ON (AH3). Nothing in the sidebar may call {@code setToolTipText} directly any more: a hover
	 * set behind this map's back is a hover the switch cannot silence, which is exactly the bug AH3 fixes.
	 *
	 * <p>Weak keys, because not every component here outlives the panel - the sort menu is rebuilt on every
	 * open (X1) and the preset cells are rebuilt whenever the bands change (Z3) - and a strong map would hold
	 * every one of them for the session.
	 */
	private final Map<JComponent, String> hoverTexts = new WeakHashMap<>();
	/** The five view switches (Q3, T1, Y1, AH), as the gear menu and the config both hold them; never null. */
	private ViewOptions options;
	/** The three quick bands the fold's chips offer and the gear menu's boxes edit (Z1); never null. */
	private BandPresets presets;
	private List<MovementRow> rows = Collections.emptyList();
	@Nullable
	private Status status;
	/** The sprites this panel has asked for, by {@code ItemManager}'s key; see {@link #image}. */
	private final Map<Long, AsyncBufferedImage> images = new HashMap<>();
	/** {@link #pruneImages}' scratch set of the keys still listed; empty between rebuilds, never read outside it. */
	private final Set<Long> liveKeys = new HashSet<>();
	/**
	 * What "now" is when the card decides whether the bank snapshot is TODAY'S ({@link #provenanceText}) and when
	 * the gear asks how long ago its menu closed ({@link #gearPressOpens}, AB1). A field so a test and the
	 * headless renderer can pin it; the client reads the wall clock, which is the only clock a Swing component
	 * has.
	 */
	private LongSupplier clock = System::currentTimeMillis;
	/**
	 * When the gear menu last became invisible, by {@link #clock} (AB1), or {@link #MENU_NEVER_CLOSED} while it
	 * never has. Written by the menu's own {@code popupMenuWillBecomeInvisible}, which is the one moment the panel
	 * can see a close however it was made - the gear, a press on the sidebar, Escape, OK, or {@link #stop()}.
	 */
	private long menuClosedAtMillis = MENU_NEVER_CLOSED;
	/** The scrollbar gutter the header is currently reserving on its right; see {@link #syncGutter}. */
	private int gutter;
	/** Set while {@link #syncHeader} restructures the header, so a focus lost to that cannot apply a bound. */
	private boolean syncingHeader;
	/** Whether the sidebar is showing this panel: false between {@link #onDeactivate} and {@link #onActivate}. */
	private boolean active = true;
	/** The publish that arrived while the sidebar was elsewhere, replayed on the next {@link #onActivate}. */
	private boolean pendingPublish;
	@Nullable
	private List<MovementRow> pendingRows;
	@Nullable
	private Status pendingStatus;
	/** Which of the Refresh link's three beats is standing right now (P2); see {@link RefreshPhase}. */
	private RefreshPhase refreshPhase = RefreshPhase.IDLE;
	/** The one-shot timer that ends the beat: it is restarted at the phase's own delay, never two timers. */
	@Nullable
	private Timer refreshTimer;

	// ---- the bank hold (addendum AS; EDT, mirrored from the plugin by setBankHold)
	/**
	 * Whether the plugin says the bank interface is open. Mirrored and never decided here: only the client thread can
	 * see the widget, and the panel acting on a guess of its own would be a second, disagreeing answer.
	 */
	private boolean bankOpen;
	/** Whether the plugin says the bank on screen is out of date and a read is owed - what the glow announces. */
	private boolean bankPending;
	/** The plugin's two counters, kept only so {@link #describe()} can hand a live run the numbers to prove it. */
	private int bankHeldEvents;
	private int bankReads;
	/**
	 * The plugin's open-bank Refresh: a local re-read of the bank with no download and no cooldown of its own (section
	 * 2.3) - the first half of a click on the link while the bank is open, the price re-check being the second (AS8) -
	 * or null while there is none. Volatile because the plugin hands it over from startUp and takes it back in
	 * shutDown, and the only reader is the link's click on the EDT - a hook written on one thread must be the hook read
	 * on the other.
	 */
	@Nullable
	private volatile Runnable bankRefresh;
	/**
	 * Set by the reader's own act while the bank is open ({@link #liftBankHold}): publishes are built again until the
	 * plugin reports a NEW change or the visit ends. The hold is for the bank's churn, not for the reader - a column
	 * picked while banking must re-order the list, and the Refresh link's own answer must be drawn.
	 */
	private boolean bankHoldLifted;
	/**
	 * Set by a click on the link ({@link #refreshNow}), which answers the glow, and cleared by the plugin's next word
	 * ({@link #setBankHold}): so the ring goes out on the click itself rather than when the read is reported.
	 */
	private boolean glowDismissed;
	/** The ring's frames ({@link #fireGlow}); null until the glow first runs, and stopped whenever it is unwanted. */
	@Nullable
	private Timer glowTimer;
	/** When the lit ring's breath began, by {@link #glowClock}: stamped as it lights, so every light starts dark. */
	private long glowStartedAtMillis;
	/**
	 * What the breath is timed by ({@link #glowLevel}): milliseconds off {@link System#nanoTime}, a clock that only
	 * moves forward, so a change of the computer's time cannot jump the ring mid-breath. A field so the tests drive a
	 * whole breath without waiting six seconds for it ({@link #setGlowClock}).
	 */
	private LongSupplier glowClock = BankPriceMovementPanel::monotonicMillis;

	/** What the rows on screen were built from; see {@link ListContext}. */
	private ListContext list;
	private int shown;
	private int rebuilds;
	private String card = CARD_LOGIN;
	private boolean foldOpen;
	/** Set while {@link #applyFilter} repaints the widgets, so the change is not saved a second time. */
	private boolean updating;
	private volatile boolean stopped;

	public BankPriceMovementPanel(ItemManager itemManager, PriceService service, Prefs prefs)
	{
		super(false);
		this.itemManager = Objects.requireNonNull(itemManager, "itemManager");
		this.service = Objects.requireNonNull(service, "service");
		this.prefs = Objects.requireNonNull(prefs, "prefs");

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		final RowFilter loaded = prefs.load();
		filter = loaded == null ? RowFilter.DEFAULT : loaded;
		// Nothing is on screen yet, so the FIRST publish is the same list restated - and a list at the top with no
		// page open stays there whichever branch it takes.
		list = new ListContext(MovementWindow.DEFAULT, null, filter);
		final HeroVisibility savedHero = prefs.loadHero();
		heroVisibility = savedHero == null ? HeroVisibility.ALL : savedHero;
		final ViewOptions savedOptions = prefs.loadOptions();
		options = savedOptions == null ? ViewOptions.DEFAULT : savedOptions;
		// Z1: before buildHeader, because the gear menu's boxes and the fold's chips are both built from them.
		final BandPresets savedPresets = prefs.loadPresets();
		presets = savedPresets == null ? BandPresets.DEFAULT : savedPresets;
		// AA1: before renderAll below, which is what puts the fold into the header - so the sidebar is never
		// painted once without it and then again with it. Null is "nothing stored", and that reads OPEN.
		final Boolean savedFold = prefs.loadFoldOpen();
		foldOpen = savedFold == null || savedFold;

		// The two gp fields: applied on Enter and on focus lost; a text that does not parse turns the field red
		// and changes nothing.
		minField = boundField(true);
		maxField = boundField(false);

		header = Widgets.column(0);
		header.setBorder(new EmptyBorder(MARGIN, MARGIN, GAP, MARGIN));
		add(header, BorderLayout.NORTH);
		buildHeader();

		// The cards. Each message is a PluginErrorPanel inside its own holder: the holder is what CardLayout
		// shows and hides, so PluginErrorPanel.setContent (which calls setVisible(true), :73) can rewrite the
		// EMPTY card's description later without un-hiding it.
		loginCard = messageCard(LOGIN_TEXT, "Your last bank is remembered once you have opened it");
		// The capture is driven by ItemContainerChanged on the bank container, which the server sends when the
		// bank interface OPENS and again on every deposit and withdrawal (ItemContainerChanged.java:30-38) - so
		// the list fills while the bank is on screen, and the old wording described something the plugin does not
		// do. The sidebar sits outside the game canvas, so the reader watches it happen.
		noBankCard = messageCard(NO_BANK_TEXT, "The list fills as soon as you open your bank");
		emptyMessage = new PluginErrorPanel();
		typeMessage(emptyMessage);
		emptyMessage.setContent(EMPTY_TEXT, "");
		// AH3: null to the factory, then through setHover - Widgets.smallButton sets the tooltip itself, and a
		// hover set behind the registry's back is one the switch cannot silence.
		clearBandButton = Widgets.smallButton(CLEAR_BAND_TEXT, null, e -> applyBand(0L, 0L));
		setHover(clearBandButton, "Show every item again, whatever its price");
		clearBandRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
		clearBandRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		clearBandRow.add(clearBandButton);
		emptyColumn = Widgets.column(GAP);
		emptyColumn.add(emptyMessage);
		emptyCard = Widgets.north(emptyColumn);

		// The 2 px gutter between row cards is the DARK_GRAY ground showing through (N section 3 §4).
		rowsColumn = Widgets.column(2);
		showMoreLabel = Widgets.linkLabel("", Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR, Color.WHITE,
			this::showMore);
		showMoreLabel.setHorizontalAlignment(SwingConstants.CENTER);
		showMoreRow = new JPanel(new BorderLayout());
		showMoreRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		showMoreRow.add(showMoreLabel, BorderLayout.CENTER);
		Widgets.fixed(showMoreRow, W, SHOW_MORE_HEIGHT);
		listColumn = Widgets.column(3);
		listColumn.setBorder(new EmptyBorder(0, MARGIN, MARGIN, MARGIN));
		listColumn.add(rowsColumn);
		listView = Widgets.north(listColumn);

		scroll = new JScrollPane(listView);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		// The header is outside the scroll pane, so the bar appearing narrows the LIST and nothing else: without
		// this the hero card and the control row would paint 7 px wider than the row cards under them, and the
		// step would come and go as the list crossed the scroll threshold (see syncGutter).
		scroll.getVerticalScrollBar().addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentShown(ComponentEvent e)
			{
				syncGutter();
			}

			@Override
			public void componentHidden(ComponentEvent e)
			{
				syncGutter();
			}

			@Override
			public void componentResized(ComponentEvent e)
			{
				syncGutter();
			}
		});

		cards = new JPanel(cardLayout);
		cards.setBackground(ColorScheme.DARK_GRAY_COLOR);
		cards.add(loginCard, CARD_LOGIN);
		cards.add(noBankCard, CARD_NO_BANK);
		cards.add(emptyCard, CARD_EMPTY);
		cards.add(scroll, CARD_LIST);
		add(cards, BorderLayout.CENTER);

		renderBounds();
		renderAll();
		cardLayout.show(cards, CARD_LOGIN);

		// Listen last, so nothing above can leave a half-built panel registered; seed from what the service
		// already knows (the bridge's currentRows/currentStatus double as the first publish for a panel built
		// after the service started), and unwind the registration if the seed throws - startUp would
		// propagate, the plugin's field would never be assigned and stop() would never run.
		listener = this::onRows;
		service.addListener(listener);
		try
		{
			final Status current = service.currentStatus();
			if (current != null)
			{
				onRows(service.currentRows(), current);
			}
		}
		catch (RuntimeException | Error e)
		{
			stopped = true;
			service.removeListener(listener);
			throw e;
		}
	}

	// ---------------------------------------------------------------- building the header

	/**
	 * Builds every header widget once: the window strip, the hero card around it, the card's menu, the
	 * control row, the fold and the problem row. The header column itself is permanent and
	 * {@link #syncHeader} puts the right rows into it.
	 */
	private void buildHeader()
	{
		// The window chips (N 4.4 control 1): opaque-free JLabels, never JButtons; the segment swallows a
		// click on the lit one and a right-button press (the card's menu is the answer to that gesture).
		final List<JLabel> cells = new ArrayList<>(MovementWindow.values().length);
		for (MovementWindow w : MovementWindow.values())
		{
			final JLabel chip = Widgets.segment(w.label(), null, () -> selectWindow(w));
			Widgets.fixed(chip, CHIP_WIDTH, CHIP_HEIGHT);
			windowChips.put(w, chip);
			cells.add(chip);
		}
		chipRow = new JPanel(new GridLayout(1, cells.size(), 0, 0));
		chipRow.setOpaque(false);
		for (JLabel cell : cells)
		{
			chipRow.add(cell);
		}
		Widgets.fixed(chipRow, CARD_INNER, CHIP_HEIGHT);

		hero = buildHero();
		// Q2: the menu moved off the card's right button and onto the gear, so the card sets NO component popup
		// and its children inherit none - a right-click anywhere on it now does nothing, which is the point of a
		// visible control. The gear opens the same JPopupMenu on a LEFT click (openGearMenu).
		heroMenu = buildHeroMenu();

		controlRow = buildControlRow();
		fold = buildFold();
		problemLabel = buildProblemLabel();
	}

	/**
	 * The hero card (N 4.2): the caption row ("Bank value" WEST, the "Refresh" link EAST), the total line (the
	 * figure WEST, the options gear EAST), the move line (triangle, gp, percent), the window strip, the
	 * provenance footnote and - last, under it - the update line (S1). Which of the total and the move line are
	 * IN the card is {@link #syncHero}'s business (O3); every line is built here so a switch never rebuilds
	 * anything.
	 *
	 * <p><b>The gear rides the total's line</b> (Q1) rather than taking one of its own, so the card gains no
	 * height for it, and it sits directly under the "Refresh" link one line above - the card's two controls in
	 * one column at its right edge. It is the one part of the card that is drawn whatever the three hero
	 * switches say: with the total hidden the LINE stays and the gear sits on it alone, because a settings
	 * control that can be switched off by a setting is a control a reader cannot get back to.
	 */
	private JPanel buildHero()
	{
		// A HeroCard and no longer a plain Widgets.column(0) since AS7: the same panel, which also paints the ring
		// round the Refresh link while a change is owed - and nothing more while none is.
		final JPanel card = new HeroCard();
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(Widgets.card(null));

		captionLabel = Widgets.label(VALUE_TITLE, Widgets.sans(11), ColorScheme.LIGHT_GRAY_COLOR);
		refreshLabel = Widgets.linkLabel(REFRESH_TEXT, Widgets.sans(11), ColorScheme.LIGHT_GRAY_COLOR,
			ColorScheme.BRAND_ORANGE, this::refreshNow);
		// AS8: set once, here, and never changed - a click does the same thing whatever the bank is doing, so one
		// sentence describes it in every state. Through setHover all the same, so "Show hover text" governs it (AH3).
		setHover(refreshLabel, REFRESH_TIP);
		// The only permanently visible action on the panel, at the smallest size on it: without padding its
		// clickable area is exactly the 39 x 15 px of the word. The inset costs nothing (the caption row asks for
		// 53 + 6 + 62 = 121 of the card's 191 px even while it says "Refreshing...") and buys ~50 x 19 px to hit.
		// A plain inset again since AS7: the ring that breathes round the link while the bank has changed under it
		// reaches outside the link's box, where no border of the link can draw, so the CARD paints it (HeroCard).
		refreshLabel.setBorder(new EmptyBorder(2, ROW_GAP, 2, 0));
		captionRow = transparentBar(ROW_GAP, captionLabel, null, refreshLabel);

		totalLabel = Widgets.label("0", Widgets.sansBold(28), Color.WHITE);
		gearLabel = iconButton(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.LIGHT_GRAY_COLOR),
			Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.BRAND_ORANGE), OPTIONS_TIP, this::openGearMenu);
		// The same 6 px of hit area the Refresh link above it buys, on the side the pointer arrives from: a 12 px
		// glyph is a 12 px target otherwise, and this one is a settings button and not a decoration.
		gearLabel.setBorder(new EmptyBorder(2, ROW_GAP, 2, 0));
		totalRow = transparentBar(ROW_GAP, totalLabel, null, gearLabel);

		triangleLabel = new JLabel();
		deltaLabel = Widgets.label("", Widgets.sansBold(18), ColorScheme.LIGHT_GRAY_COLOR);
		deltaLabel.setBorder(new EmptyBorder(0, 0, 0, MOVE_GAP));
		pctLabel = Widgets.label("", Widgets.sansBold(18), ColorScheme.LIGHT_GRAY_COLOR);
		moveLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		moveLine.setOpaque(false);
		moveLine.add(triangleLabel);
		moveLine.add(deltaLabel);
		moveLine.add(pctLabel);

		// The strip's own preferred size is pinned, so it sits in a holder that carries the gaps around it.
		stripHolder = new JPanel(new BorderLayout());
		stripHolder.setOpaque(false);
		stripHolder.setBorder(new EmptyBorder(GAP, 0, 2, 0));
		stripHolder.add(chipRow, BorderLayout.CENTER);

		footnoteLabel = Widgets.label("", Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR);
		// S1: the same face and the same grey as the footnote it sits under - the two grey lines under the chips
		// are one pair, one saying WHERE the figures come from and one saying HOW OFTEN they move. It has exactly
		// TWO texts since addendum T (T5, {@link #updateText}) and is never fitted: both are measured against the
		// card's 191 px in WidgetsTest instead (S3), the way the caption is. {@link #renderValue} keeps it in step
		// with the switch; this seeds it so the card reads right before the first publish.
		updateLabel = Widgets.label(updateText(options), Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR);

		card.add(captionRow);
		card.add(totalRow);
		card.add(moveLine);
		card.add(stripHolder);
		card.add(footnoteLabel);
		card.add(updateLabel);
		// Neither the gear nor the update line is a tip target: each keeps its own tooltip. A settings control
		// that explained the bank's sums on hover would be the one thing on the card that does not say what it
		// does, and the update line's whole point is the sentence behind IT (S2).
		heroTipTargets.addAll(Arrays.asList(card, captionRow, captionLabel, totalRow, totalLabel, moveLine,
			triangleLabel, deltaLabel, pctLabel, stripHolder, footnoteLabel));
		return card;
	}

	/**
	 * The gear menu (Q2, the card's right-click menu of N 3.1 / O4 moved onto a visible control): "Refresh
	 * prices now", a separator, the three card check items "Show bank value" / "Show change in gp" / "Show
	 * change in %", a separator, and the four view check items "Use live prices" (T1) / "Include coins and platinum
	 * tokens" / "Include untradeable items" / "Include inventory and worn gear" (Y1) -
	 * two groups, because the first three say what the CARD draws and the last four what the figures MEAN.
	 *
	 * <p>Every check item reflects the switch it carries and writes it: the card's three go through
	 * {@link #setHeroVisibility} and the view's four through {@link #setOptions}, which repaint at once and
	 * write the choice to the prefs so the stored config follows. They are {@link JCheckBoxMenuItem}s - plain
	 * Swing, painted by whatever menu UI the client's look and feel installs - and {@link #syncHeroMenu} keeps
	 * their ticks in step with the switches, which matters when the change came from the settings page instead.
	 *
	 * <p>The eight entries read in the order Y4 lists them, and the carried one is placed by what it MEANS: it is a
	 * third "Include", so it goes with the other two. It ends the group because addendum AO deleted the row switch
	 * that used to close it - addendum AN's three-line row prints the stack AND one item, so there was no longer a
	 * reading for a switch to pick.
	 *
	 * <p><b>A third group since addendum Z</b> (lines Z1-Z2;
	 * {@code docs/bank-price-movement-addendum-Z-2026-09-13.md}): after another separator, the
	 * {@link #buildPresetRow() preset row} - a caption and three boxes, not a switch - and the
	 * "Reset to default" item under it. They come after the switches because they are the only thing in this menu
	 * that edits a control elsewhere on the panel rather than the panel's own reading of the bank, and they are
	 * HERE because the fold they edit has no room to hold its own settings.
	 *
	 * <p><b>And one row after all of them</b> (addendum AB, line AB2): the {@link #buildOkRow() OK row}, the menu's
	 * drawn way out. It is last because it is what a reader presses when everything above it is as they want it.
	 */
	private JPopupMenu buildHeroMenu()
	{
		final JPopupMenu menu = new JPopupMenu();
		menu.setBorder(new EmptyBorder(5, 5, 5, 5));
		final JMenuItem refresh = new JMenuItem(REFRESH_MENU_TEXT);
		refresh.setFont(Widgets.sans(12));
		// AS 7.1 decision 2, kept by AS8: its name says PRICES, so it stays the price re-check ALONE with the bank open
		// too - only the link also re-reads the items, because the link is what glows.
		refresh.addActionListener(e -> refreshPricesNow());
		menu.add(refresh);
		menu.addSeparator();
		showValueItem = checkItem(SHOW_VALUE_TEXT, SHOW_VALUE_TIP, on -> setHeroVisibility(heroVisibility.withValue(on)));
		showGpItem = checkItem(SHOW_GP_TEXT, SHOW_GP_TIP, on -> setHeroVisibility(heroVisibility.withGp(on)));
		showPctItem = checkItem(SHOW_PCT_TEXT, SHOW_PCT_TIP, on -> setHeroVisibility(heroVisibility.withPct(on)));
		menu.add(showValueItem);
		menu.add(showGpItem);
		menu.add(showPctItem);
		menu.addSeparator();
		livePricesItem = checkItem(LIVE_PRICES_TEXT, LIVE_PRICES_TIP, on -> setOptions(options.withLivePrices(on)));
		countCashItem = checkItem(COUNT_CASH_TEXT, COUNT_CASH_TIP, on -> setOptions(options.withCountCash(on)));
		countUntradeablesItem = checkItem(COUNT_UNTRADEABLES_TEXT, COUNT_UNTRADEABLES_TIP,
			on -> setOptions(options.withCountUntradeables(on)));
		countInventoryItem = checkItem(COUNT_INVENTORY_TEXT, COUNT_INVENTORY_TIP,
			on -> setOptions(options.withCountInventory(on)));
		// T1: first of the group, before the two of addendum Q - the series the figures are read from, then what
		// is counted. Y1's switch is the third thing COUNTED, so it lands directly after the untradeables, and
		// since addendum AO it is the last of them.
		menu.add(livePricesItem);
		menu.add(countCashItem);
		menu.add(countUntradeablesItem);
		menu.add(countInventoryItem);
		// Z2: a third group, and the only one that is not a list of switches - the three quick bands, in boxes,
		// with the way back to 100k / 1m / 10m now a button in the bottom row (AH2).
		menu.addSeparator();
		presetRow = buildPresetRow();
		menu.add(presetRow);
		// AH, placed by the user: the last ITEM in the menu, in the row above OK - the box on the left, the
		// label to its right, both hard against the menu's left edge, which is what a JMenuItem with an icon
		// lays out by itself.
		showHoverTextItem = new JMenuItem(SHOW_HOVER_TEXT_TEXT, Widgets.checkBox(false));
		showHoverTextItem.setFont(Widgets.sans(12));
		setHover(showHoverTextItem, SHOW_HOVER_TEXT_TIP);
		showHoverTextItem.setHorizontalAlignment(SwingConstants.LEFT);
		showHoverTextItem.setHorizontalTextPosition(SwingConstants.RIGHT);
		showHoverTextItem.addActionListener(e -> setOptions(options.withShowHoverText(!options.showHoverText())));
		menu.add(showHoverTextItem);
		// AB2: and under everything, the way out.
		okRow = buildOkRow();
		menu.add(okRow);
		menu.addPopupMenuListener(new PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(PopupMenuEvent e)
			{
				// The menu always opens on the presets in force: a box left red by a refused edit is a question
				// that was answered ("not saved"), and re-asking it on the next open would be the only place in
				// this sidebar where a control kept text the plugin does not believe.
				renderPresetFields();
				// Z6: ...and on NO box. The popup's window is focusable because of the row (see buildPresetRow),
				// and Swing hands a fresh focusable window's focus to the first focusable thing in it - which was
				// the first band, caret blinking, before the reader had asked for it. The ROW takes it instead, so
				// nothing is targeted until a box is clicked. invokeLater because that initial focus is assigned
				// after this event, when the window is actually shown: a request made now would be overruled.
				// Through menuFocusTarget() so that the target a test can name is the target the menu asks for.
				SwingUtilities.invokeLater(() -> menuFocusTarget().requestFocusInWindow());
			}

			@Override
			public void popupMenuWillBecomeInvisible(PopupMenuEvent e)
			{
				// Z2: closing the menu is the third way to commit - a reader who types and clicks the gear away
				// has finished editing, and the two fields one control row down have taught them that leaving a
				// box applies it.
				commitPresets();
				// AB1: and this is where the panel learns that its menu went away, whoever took it away. The
				// grabber's cancel arrives here BEFORE the gear's own press listener runs, which is what lets the
				// gear read that press as the close half of a toggle (gearPressOpens).
				menuClosedAtMillis = clock.getAsLong();
			}

			@Override
			public void popupMenuCanceled(PopupMenuEvent e)
			{
			}
		});
		syncHeroMenu();
		return menu;
	}

	/**
	 * The gear menu's box row (Z2): the grey caption {@link #PRESETS_TEXT} over three boxes in the Min / Max
	 * fields' style, one per band, smallest first and prefilled in gp shorthand.
	 *
	 * <p><b>A {@link JPanel} and not a menu item</b>, which is the whole mechanism: {@code PopupFactory} makes a
	 * heavy-weight popup's window FOCUSABLE exactly when the popup holds a child that is neither a
	 * {@code MenuElement} nor a {@code JSeparator} ({@code PopupFactory.java}, {@code focusPopup}), and RuneLite
	 * forces every popup heavy-weight so the game applet cannot obscure it
	 * ({@code ClientUI.setupDefaults}). So this row is what lets the boxes take the keyboard at all;
	 * without it the popup window would refuse focus and every keystroke would go to the game. A press inside the
	 * popup's own hierarchy never cancels it either ({@code BasicPopupMenuUI.MouseGrabber.isInPopup}), which is
	 * the rest of what Z2 asks for: clicking a field must not close the menu.
	 *
	 * <p>The caption sits ABOVE the boxes rather than beside them because a popup is as wide as its widest child:
	 * "Preset price ranges" beside three 56 px boxes would ask for close to 300 px and widen the whole menu past
	 * its longest sentence, and the three boxes alone are narrower than "Include coins and platinum tokens"
	 * already is - as is the caption itself (Z7, AB3).
	 *
	 * <p><b>The row is FOCUSABLE</b> (line Z6), which is the other half of that mechanism and the user's own
	 * correction after the first hand look: "currently when you click the gear settings icon the 100k+ field is
	 * already selected and cursor is blinking there, i dont want that until its clicked". A focusable window hands
	 * its focus to the first focusable component in it, and until now that was the first band's text field. A plain
	 * {@link JPanel} is not focusable ({@code DefaultFocusTraversalPolicy.accept} asks a lightweight peer, which
	 * says no), so {@code setFocusable(true)} both puts the row ahead of its own boxes in the traversal cycle and
	 * lets the {@link #buildHeroMenu() menu's open event} park the keyboard on it ({@link #menuFocusTarget()} is
	 * that target). The boxes stay ordinary text fields: a click focuses one and everything after it (Enter, focus
	 * lost, the menu closing) is unchanged.
	 */
	private JPanel buildPresetRow()
	{
		final JPanel row = Widgets.column(2);
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(4, ROW_GAP, 2, ROW_GAP));
		row.setFocusable(true);
		final JLabel caption = Widgets.label(PRESETS_TEXT, Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR);
		setHover(caption, PRESETS_TIP);
		final JPanel boxes = new JPanel();
		boxes.setLayout(new BoxLayout(boxes, BoxLayout.X_AXIS));
		boxes.setOpaque(false);
		for (int i = 0; i < presetFields.length; i++)
		{
			if (i > 0)
			{
				boxes.add(Box.createHorizontalStrut(PRESET_GAP));
			}
			presetFields[i] = presetBox(i);
			boxes.add(presetFields[i]);
		}
		// The glue keeps the three at their pinned widths when the menu is wider than they are - which it is,
		// because the check items above them are sentences.
		boxes.add(Box.createHorizontalGlue());
		row.add(caption);
		row.add(boxes);
		setHover(row, PRESETS_TIP);
		renderPresetFields();
		return row;
	}

	/**
	 * One preset box (Z2). It commits on Enter and on focus lost, exactly as the fold's gp fields do - and the
	 * three are read TOGETHER by {@link #commitPresets}, because a band is only valid beside the other two.
	 *
	 * <p>Its placeholder is that slot's DEFAULT band ("100k", "1m", "10m"), so a box emptied by mistake says what
	 * belongs in it and what "Reset to default" would put back. An empty box is not a band, so committing one is
	 * refused like any other unreadable text.
	 */
	private Widgets.PlaceholderField presetBox(int i)
	{
		final Widgets.PlaceholderField field = Widgets.gpField(MovementMath.formatGp(BandPresets.DEFAULT.mins()[i]));
		final String tip = "Quick band " + (i + 1) + " of " + presetFields.length
			+ ", in gp shorthand - e.g. 1m; the three must differ";
		// On the panel AND the text field, as the fold's fields do: the field is the mouse target inside its panel.
		setHover(field, tip);
		setHover(field.getTextField(), tip);
		Widgets.fixed(field, PRESET_FIELD_WIDTH, field.getPreferredSize().height);
		field.addActionListener(e -> commitPresets());
		field.getTextField().addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				// A temporary loss is the window going away, not the reader leaving the box - the same rule the
				// Min / Max fields follow.
				if (!e.isTemporary())
				{
					commitPresets();
				}
			}
		});
		return field;
	}

	/**
	 * The gear menu's last row (addendum AB, line AB2): a horizontal glue and, pushed to the right end of the menu
	 * by it, one small button reading {@link #OK_TEXT}.
	 *
	 * <p>The user asked for it in the same breath as the toggle - "also put a 'save' or 'ok' button at the bottom
	 * right of the gear settings menu that will close the settings box as well" - and it answers a real question
	 * this menu had left open: every other way out of it is a gesture (press the sidebar, press Escape, press the
	 * gear again) and none of them is drawn anywhere. A reader who has just typed three bands wants a control that
	 * says "done".
	 *
	 * <p>A {@link JPanel} carrying a {@link JButton}, like the {@link #buildPresetRow() preset row} above it, and
	 * for the same two reasons: a press inside the popup's own hierarchy never cancels it
	 * ({@code BasicPopupMenuUI.MouseGrabber.isInPopup}), so the button gets its click, and a non-{@code MenuElement}
	 * child is what makes RuneLite's heavy-weight popup focusable at all (Z2). It is the one control in this menu
	 * drawn by the look and feel's {@code ButtonUI} - {@link Widgets#smallButton}'s reasoning, that the way OUT of
	 * something should look like the platform's own control - wearing the menu's own face rather than the
	 * RuneScape small font, so it reads as part of the list it closes.
	 *
	 * <p>The row is as wide as the button and no wider: the glue has no preferred width, so the menu's width is
	 * still set by its longest sentence, and the button rides the right edge whatever that turns out to be.
	 */
	private JPanel buildOkRow()
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(2, ROW_GAP, 2, ROW_GAP));
		// AH2: "Reset to default" shares this row now, hard LEFT, with OK still hard right - the user, looking
		// at the menu: "i would like the 'reset to default' button to be in the orange box alongside 'OK' ...
		// just aligned left, OK button align right as it is currently is". A JMenuItem cannot sit in a BoxLayout
		// row and still look like one, so it is a small button like OK's, carrying the same text, the same
		// tooltip and the same action as the menu item it replaces.
		resetPresetsButton = Widgets.smallButton(RESET_PRESETS_TEXT, null, e -> setPresets(BandPresets.DEFAULT));
		setHover(resetPresetsButton, RESET_PRESETS_TIP);
		resetPresetsButton.setFont(Widgets.sans(12));
		resetPresetsButton.setMaximumSize(resetPresetsButton.getPreferredSize());
		okButton = Widgets.smallButton(OK_TEXT, null, e -> pressOk());
		setHover(okButton, OK_TIP);
		okButton.setFont(Widgets.sans(12));
		// Read AFTER the font is set, and pinned so no ButtonUI can decide to fill the row with a 180 px "OK":
		// BoxLayout stretches a child up to its MAXIMUM, and the button's must stay the size of the word for the
		// glue to have anything to push against.
		okButton.setMaximumSize(okButton.getPreferredSize());
		// Reset first, then the glue, then OK: BoxLayout hands the row's spare width to the glue, so the one
		// before it is pinned left and the one after it right, however wide the menu is.
		row.add(resetPresetsButton);
		row.add(Box.createHorizontalGlue());
		row.add(okButton);
		return row;
	}

	/**
	 * The OK button's press (AB2): commit the three boxes, then take the menu down.
	 *
	 * <p>Both halves are spelled out even though each is implied by the other - the close commits (Z2) and a
	 * commit does not close - because the button's promise is that what is on screen is what is saved, and it must
	 * hold whichever way Swing delivers the hide. A trio that does not read is still refused, in red, and the menu
	 * still closes: the refusal was answered where it was made, and the next open asks the question afresh
	 * ({@code popupMenuWillBecomeVisible} re-prints the bands in force).
	 */
	private void pressOk()
	{
		commitPresets();
		closeGearMenu();
	}

	/**
	 * Opens the gear menu under the gear (Q1) - {@code menu.show(anchor, x, y)} behind an {@code isShowing()}
	 * guard, the same rule {@link #openSortMenu} follows: RuneLite disables lightweight popups, and a menu can
	 * only be placed against something that is on the screen.
	 *
	 * <p><b>A press while the menu stands opens nothing</b> (addendum AB, line AB1), which is what makes the gear a
	 * toggle. The press arrives here in one of two orders, and both are answered: in this client the popup has
	 * already been cancelled by the same press (the grabber runs first), so the panel asks
	 * {@link #gearPressOpens()} how long ago the menu closed rather than asking the menu whether it is open; and
	 * should a popup ever survive the press that reached the gear, it is taken down here instead. Either way the
	 * second click closes and opens nothing.
	 */
	void openGearMenu()
	{
		if (stopped)
		{
			return;
		}
		if (heroMenu.isVisible())
		{
			closeGearMenu();
			return;
		}
		if (!gearPressOpens() || !gearLabel.isShowing())
		{
			return;
		}
		heroMenu.show(gearLabel, 0, gearLabel.getHeight());
	}

	/**
	 * Whether a press on the gear right now OPENS the menu (AB1): true unless the menu went away within the last
	 * {@link #GEAR_REOPEN_GUARD_MILLIS}, in which case this press is the one that took it away and its work is
	 * done.
	 *
	 * <p>Named, and package-private, for the reason {@link #menuFocusTarget()} is: what a press decides can be
	 * asked of a panel that is not on any screen, and whether a popup is SHOWING cannot - a test has no window to
	 * show one in. The clock is {@link #setClock the panel's}, so a test drives this by moving it.
	 */
	boolean gearPressOpens()
	{
		return menuClosedAtMillis == MENU_NEVER_CLOSED
			|| clock.getAsLong() - menuClosedAtMillis >= GEAR_REOPEN_GUARD_MILLIS;
	}

	/**
	 * One check item of the card's menu. The action fires AFTER the item's model has toggled (a click on a
	 * {@code JCheckBoxMenuItem} flips its selection before the {@code ActionEvent}), so {@code isSelected()}
	 * in the listener is the NEW state; {@code setSelected} from {@link #syncHeroMenu} fires no action, which is
	 * what keeps the config round trip from writing the config back.
	 */
	// Not static since AH3: every hover in this panel is registered on the instance, so a factory that sets
	// one belongs to the instance too.
	private JCheckBoxMenuItem checkItem(String text, String tooltip, Consumer<Boolean> onToggle)
	{
		final JCheckBoxMenuItem item = new JCheckBoxMenuItem(text);
		// Every menu item in this sidebar sets its face: one that does not is drawn by the look and feel in the
		// 16 px bitmap RuneScape default, and this menu would then look nothing like the sort menu one control
		// row away. The TICK stays the look and feel's - it is what a reader expects of a settings toggle.
		item.setFont(Widgets.sans(12));
		setHover(item, tooltip);
		item.addActionListener(e -> onToggle.accept(item.isSelected()));
		return item;
	}

	/**
	 * The control row (N 3.3): the band button WEST, the sort word-button EAST (AM) - a word-button that states
	 * its band ("All items v" / "100k - 5m v", N 4.4 control 3) and opens the price fold - between two 1 px
	 * rules.
	 *
	 * <p>The two buttons wear the same 7 px triangle for two different jobs (W2). The band button's points down
	 * for good: it is a MENU MARKER, the mark every "this opens something" control on the panel carries. The sort
	 * button's is the DIRECTION - down for biggest first, up for smallest - so it is not set here at all;
	 * {@link #renderControl} owns it, as it owns the text, and the constructor's {@link #renderAll} paints both
	 * before the panel is ever shown. A second marker beside it was considered and dropped: the sort button is the
	 * only thing on its half of the row, and two triangles on one word are two directions to read.
	 */
	private JPanel buildControlRow()
	{
		sortButton = Widgets.linkLabel("", Widgets.sansBold(12), ColorScheme.TEXT_COLOR, ColorScheme.BRAND_ORANGE,
			this::openSortMenu);
		sortButton.setHorizontalTextPosition(SwingConstants.LEFT);
		sortButton.setIconTextGap(ICON_GAP);

		bandTarget = Widgets.linkLabel("", Widgets.sans(12), ColorScheme.TEXT_COLOR, ColorScheme.BRAND_ORANGE,
			this::toggleFold);
		bandTarget.setIcon(Widgets.triangle(true));
		bandTarget.setHorizontalTextPosition(SwingConstants.LEFT);
		bandTarget.setIconTextGap(ICON_GAP);

		final JPanel row = new JPanel(new BorderLayout(ROW_GAP, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new CompoundBorder(new EmptyBorder(ROW_GAP, 0, 0, 0),
			new CompoundBorder(new MatteBorder(1, 0, 1, 0, ColorScheme.BORDER_COLOR), new EmptyBorder(0, ROW_GAP, 0, ROW_GAP))));
		// AK, the user's placement: the price band on the LEFT and the sort column on the RIGHT - they were the
		// other way round from addendum N until now. The two keep their own fitting rules; only the slots swap,
		// so the band still gets what the sort leaves and the sort is still the one that never truncates.
		row.add(bandTarget, BorderLayout.WEST);
		row.add(sortButton, BorderLayout.EAST);
		Widgets.fixed(row, W, ROW_GAP + 2 + CONTROL_HEIGHT);
		return row;
	}

	/**
	 * The price fold (N 3.5): the four presets over the two gp fields and the "x" that clears both. A plain
	 * panel and not a popup, so no MenuSelectionManager steals keystrokes from a text field; added under the
	 * control row while open and removed on the second tap - never hidden.
	 *
	 * <p>Since addendum Z (line Z3) the four chips are "All" and the reader's three {@link BandPresets}: each cell
	 * is built once and asks {@link #presetMin} for the band its place holds, so editing the presets in the gear
	 * menu re-labels them ({@link #renderPresetCells}) without rebuilding the fold. With the DEFAULT presets they
	 * are "All" / "100k+" / "1m+" / "10m+" - the four this fold has always drawn.
	 */
	private JPanel buildFold()
	{
		final JPanel panel = Widgets.column(5);
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(ROW_GAP, GAP, ROW_GAP, GAP));

		for (int i = 0; i < presetCells.length; i++)
		{
			// Z3: the INDEX is captured, never the band - a cell built once goes on pressing whatever band its
			// place holds after the reader edits the presets, so nothing here is rebuilt when they do.
			final int index = i;
			final JLabel cell = Widgets.segment("", null, () -> applyBand(presetMin(index), 0L));
			Widgets.fixed(cell, PRESET_WIDTH, PRESET_HEIGHT);
			presetCells[i] = cell;
		}
		renderPresetCells();
		final JPanel presetBar = Widgets.grid(PRESET_GAP, presetCells);
		presetBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		clearLabel = iconButton(Widgets.clearIcon(ColorScheme.LIGHT_GRAY_COLOR),
			Widgets.clearIcon(ColorScheme.BRAND_ORANGE), "Clear both bounds", () -> applyBand(0L, 0L));
		Widgets.fixed(clearLabel, SMALL_ICON, minField.getPreferredSize().height);
		// A box with struts, not a FlowLayout: FlowLayout's preferred width adds its gap at BOTH ends as well as
		// between the parts (90 + 90 + 11 with four gaps = 211, over the fold's 205), and the width rule is
		// measured on preferred sizes. The glue keeps the "x" beside the fields when the sidebar is wider.
		final JPanel fields = new JPanel();
		fields.setLayout(new BoxLayout(fields, BoxLayout.X_AXIS));
		fields.setOpaque(false);
		fields.add(minField);
		fields.add(Box.createHorizontalStrut(ICON_GAP));
		fields.add(maxField);
		fields.add(Box.createHorizontalStrut(ICON_GAP));
		fields.add(clearLabel);
		fields.add(Box.createHorizontalGlue());

		panel.add(presetBar);
		panel.add(fields);
		return panel;
	}

	/**
	 * The problem row (N 3.6): one 12 px line, its height taken from a probe so it never collapses while
	 * empty, fitted to the width with the whole sentence as its tooltip.
	 */
	private JLabel buildProblemLabel()
	{
		final JLabel label = Widgets.label("", Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR);
		label.setBorder(new EmptyBorder(2, ROW_GAP, 0, ROW_GAP));
		final int probe = Widgets.label("X", Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR).getPreferredSize().height;
		Widgets.fixed(label, W, probe + 2);
		return label;
	}

	private Widgets.PlaceholderField boundField(boolean min)
	{
		final Widgets.PlaceholderField field = Widgets.gpField(min ? "min gp" : "max gp");
		final String tip = (min ? "Lowest" : "Highest") + " unit price to show, e.g. 100k or 1.5m; empty = no "
			+ (min ? "lower" : "upper") + " bound";
		// On the panel AND the text field: the field is the mouse target inside its panel (playbook 7.5).
		setHover(field, tip);
		setHover(field.getTextField(), tip);
		Widgets.fixed(field, FIELD_WIDTH, field.getPreferredSize().height);
		field.addActionListener(e -> applyBound(field, min));
		field.getTextField().addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(FocusEvent e)
			{
				// Not while the header itself is taking the field away (see syncHeader): that is not the user
				// leaving the field, and what is in it is half a number.
				if (!e.isTemporary() && !syncingHeader)
				{
					applyBound(field, min);
				}
			}
		});
		return field;
	}

	/**
	 * An icon-only control: grey at rest, {@code hot} while the mouse is over it, a LEFT press runs
	 * {@code onClick} ({@link Widgets#isPress}: a right-button press is a menu gesture everywhere in this
	 * sidebar, never a press).
	 */
	/** Not static since AH3 - see {@link #checkItem}. */
	private JLabel iconButton(ImageIcon rest, ImageIcon hot, String tooltip, Runnable onClick)
	{
		final JLabel label = new JLabel(rest);
		setHover(label, tooltip);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (Widgets.isPress(e))
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

	/** {@link Widgets#bar} on a see-through ground, for the rows inside the hero card. */
	private static JPanel transparentBar(int gap, @Nullable JComponent west, @Nullable JComponent centre,
		@Nullable JComponent east)
	{
		final JPanel bar = Widgets.bar(gap, west, centre, east);
		bar.setOpaque(false);
		return bar;
	}

	private static JPanel messageCard(String title, String description)
	{
		final PluginErrorPanel message = new PluginErrorPanel();
		typeMessage(message);
		message.setContent(title, description);
		return Widgets.north(message);
	}

	/**
	 * Puts a {@link PluginErrorPanel}'s two labels into THIS panel's type (N section 3 §2), because it sets
	 * neither: its title inherits the look and feel's default - under {@code RuneLiteLAF} the 16 px bitmap
	 * RuneScape face ({@code RuneLiteLAF.java:132}) - and its description is
	 * {@code getRunescapeSmallFont()} in {@code Color.GRAY}, which measures 3.73:1 on the sidebar's ground.
	 * These three cards are the FIRST screen a new user sees, so the panel would introduce itself in one
	 * typeface and then work in another.
	 *
	 * <p>The labels are private to that class, so they are reached as its children - the title in
	 * {@code BorderLayout.NORTH}, the description in {@code CENTER} ({@code PluginErrorPanel.java:53-61}).
	 * {@code setContent} only sets text and calls {@code setVisible}, so a font set once here survives every
	 * later rewrite of the EMPTY card's description.
	 */
	private static void typeMessage(PluginErrorPanel message)
	{
		final Component title = ((BorderLayout) message.getLayout()).getLayoutComponent(BorderLayout.NORTH);
		final Component description = ((BorderLayout) message.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		if (title instanceof JLabel)
		{
			title.setFont(Widgets.sansBold(16));
			title.setForeground(Color.WHITE);
		}
		if (description instanceof JLabel)
		{
			description.setFont(Widgets.sans(12));
			description.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
	}

	/**
	 * Puts exactly {@code want}, in order, into {@code parent} - and touches nothing when the set is already
	 * right, so a repaint that changes nothing causes no flicker (C30). Rows and lines are added and removed,
	 * never hidden: a hidden component still takes its place in a {@code DynamicGridLayout} column (playbook
	 * 7.5).
	 *
	 * <p><b>A diff, not a rebuild.</b> Only the children that are NOT wanted are removed, and only the missing
	 * ones are added, each at its place; a child that stays is never removed and never re-added. That matters
	 * because {@code removeAll()} tears down the peer of everything under it: with the price fold open and the
	 * caret in a gp field, a publish that merely added or dropped the problem row took the focus off that field
	 * permanently, and the field's own focus-lost handler then applied whatever half of "100k" had been typed.
	 * ({@code Container.addImpl} removes a component from its current parent first, so re-adding a stayer would
	 * be exactly the same tear-down - hence inserting the missing ones instead.) The header's four rows always
	 * keep their relative order, which is what makes the insert land the right list; anything else falls back to
	 * the wholesale replacement rather than painting a wrong card.
	 *
	 * @return whether anything moved
	 */
	private static boolean replaceChildren(Container parent, List<Component> want)
	{
		if (want.equals(Arrays.asList(parent.getComponents())))
		{
			return false;
		}
		for (int i = parent.getComponentCount() - 1; i >= 0; i--)
		{
			if (!want.contains(parent.getComponent(i)))
			{
				parent.remove(i);
			}
		}
		for (int i = 0; i < want.size(); i++)
		{
			final Component c = want.get(i);
			if (i >= parent.getComponentCount() || parent.getComponent(i) != c)
			{
				parent.add(c, i);
			}
		}
		if (!want.equals(Arrays.asList(parent.getComponents())))
		{
			parent.removeAll();
			for (Component c : want)
			{
				parent.add(c);
			}
		}
		parent.revalidate();
		parent.repaint();
		return true;
	}

	/**
	 * Keeps the pinned header exactly as wide as the row cards scrolling under it.
	 *
	 * <p>The panel is {@code super(false)}, so RuneLite gives it {@code PANEL_WIDTH + SCROLLBAR_WIDTH} = 242 px
	 * ({@code PluginPanel.java:88-92}), not the 225 px {@link #MARGIN} is derived from. The header sits in
	 * {@code BorderLayout.NORTH} of that (contract C28 - a header inside the scroll pane would scroll away) and
	 * the list sits inside {@link #scroll}, so the moment the vertical bar appears the LIST loses its width (7 px
	 * under {@code RuneLiteLAF}) and the header does not: two {@code DARKER_GRAY} cards on one ground with their
	 * right edges 7 px apart, appearing and disappearing as the list crosses the scroll threshold.
	 *
	 * <p>So the header reserves the same gutter, MEASURED off the bar rather than hard-coded - the look and feel
	 * owns that number and the test JVM's is not the client's - and gives it back when the bar goes. The policy
	 * stays {@code AS_NEEDED}: reserving it always would leave a permanent empty track, and at a narrower host
	 * width it would push the header's content under the 213 px the design is measured at.
	 */
	private void syncGutter()
	{
		final Component bar = scroll.getVerticalScrollBar();
		final int want = bar.isVisible() ? bar.getWidth() : 0;
		if (want == gutter)
		{
			return;
		}
		gutter = want;
		header.setBorder(new EmptyBorder(MARGIN, MARGIN, GAP, MARGIN + want));
		header.revalidate();
		header.repaint();
	}

	/** The gutter the header reserves for the list's scrollbar right now, in px (0 while there is no bar). */
	int gutter()
	{
		return gutter;
	}

	/**
	 * Syncs the gutter before laying out, so a header built at one width is measured against the list at the
	 * same one. The bar's own {@code ComponentListener} catches the case where it is the bar, and not the panel,
	 * that changed.
	 */
	@Override
	public void doLayout()
	{
		syncGutter();
		super.doLayout();
	}

	// ---------------------------------------------------------------- Activatable (sidebar shown / hidden)

	/**
	 * The sidebar opened on this panel: the service may fetch and schedule (contract C33 / C24).
	 *
	 * <p>Who calls this: {@code PluginPanel implements Activatable} ({@code PluginPanel.java:36},
	 * {@code Activatable.java:29-33} - default no-op hooks), and the sidebar's tab-change listener hands the
	 * outgoing panel to {@code SwingUtil.deactivate} and the incoming one to {@code SwingUtil.activate}
	 * ({@code ClientUI.java:407-441}, clone tag runelite-parent-1.12.37). Closing the sidebar deactivates the
	 * selected panel the same way, which is what makes "nothing while hidden" (design D8) hold.
	 *
	 * <p>Coming back does not end addendum AS's hold: a reader who opens the sidebar in the middle of banking gets the
	 * list as it stood and the glowing link, and the stored publish waits for the bank to close or for their click -
	 * "nothing redraws while you bank unless you click" (section 7.1).
	 */
	@Override
	public void onActivate()
	{
		if (stopped)
		{
			return;
		}
		active = true;
		// The stored publish, replayed - unless the bank is open, which holds it exactly as hiding did (AS 7.2).
		releaseHeld();
		// ...and the ring, if a change is owed: the reader can see the link again.
		syncGlow();
		service.setVisible(true);
	}

	/**
	 * The sidebar closed or moved on: nothing is fetched while nobody is looking (design D8), and nothing is
	 * BUILT either - {@link #onRows} stores the next publish instead of laying out a page of rows nobody can see
	 * ({@link #active}). The ring round the Refresh link goes out too (AS 7.3): 25 repaints a second of a ring
	 * nobody can see is the cost addendum AS exists to take away.
	 */
	@Override
	public void onDeactivate()
	{
		closeMenus();
		active = false;
		syncGlow();
		if (!stopped)
		{
			service.setVisible(false);
		}
	}

	/**
	 * shutDown: stops listening, drops the rows, and leaves nothing ticking - the acknowledgement's timer and the
	 * glow's (AGENTS.md: scheduled work is cancelled in shutDown). The plugin's open-bank hook is dropped as well, so
	 * a dead panel pins no plugin. Idempotent; the panel is dead afterwards.
	 */
	public void stop()
	{
		stopped = true;
		closeMenus();
		clearRefreshAck();
		stopGlow();
		bankRefresh = null;
		service.removeListener(listener);
		rows = Collections.emptyList();
		pendingPublish = false;
		pendingRows = null;
		pendingStatus = null;
		images.clear();
		rowsColumn.removeAll();
		shown = 0;
		updateShowMore();
		renderControl();
		rowsColumn.revalidate();
		rowsColumn.repaint();
	}

	// ---------------------------------------------------------------- the hero switches (EDT, O2-O4)

	/**
	 * The switches changed under us (the plugin's {@code ConfigChanged} for one of the three keys): puts the
	 * right lines into the card, repaints it and its tooltip, and ticks the menu to match. Saves nothing back -
	 * the config already knows - and the same value again is a harmless repaint. The card's OWN write does not
	 * come back this way at all: the plugin marks {@code saveHero}'s three keys as its own and drops the events
	 * they post ({@code BankPriceMovementPlugin.prefsWriter}), because {@link #setHeroVisibility} has already
	 * applied the whole value here. Null reads as {@link HeroVisibility#ALL}.
	 */
	public void applyHeroVisibility(@Nullable HeroVisibility next)
	{
		if (stopped)
		{
			return;
		}
		heroVisibility = next == null ? HeroVisibility.ALL : next;
		syncHeroMenu();
		renderValue();
	}

	/**
	 * The card's own switch (O4: a check item of its right-click menu): applies at once AND writes the choice
	 * through {@link Prefs#saveHero}, so the stored config follows; the {@code ConfigChanged} events that write
	 * posts are the plugin's own and are dropped there rather than re-rendering this card once per key (see
	 * {@link #applyHeroVisibility}). Nothing is written when nothing changed.
	 *
	 * <p>An ALREADY-OPEN settings page keeps the old tick until it is re-entered from the plugin list - RuneLite
	 * rebuilds a {@code ConfigPanel} only then (see the class javadoc). The value on disk is right either way,
	 * and {@code bpm state | jq .result.hero} shows it at once.
	 */
	public void setHeroVisibility(@Nullable HeroVisibility next)
	{
		final HeroVisibility v = next == null ? HeroVisibility.ALL : next;
		final boolean changed = !v.equals(heroVisibility);
		applyHeroVisibility(v);
		if (changed && !updating)
		{
			prefs.saveHero(v);
		}
	}

	/** Which of the card's three figures are drawn right now; never null. */
	public HeroVisibility heroVisibility()
	{
		return heroVisibility;
	}

	/**
	 * Pins what the card calls "today" when it stamps the bank snapshot ({@link #provenanceText}), and what the
	 * gear calls "just now" when it asks how long ago its menu closed ({@link #gearPressOpens}, AB1) - for the
	 * tests and the headless renderer, whose fixtures carry a fixed capture instant. The client leaves it at the
	 * wall clock.
	 */
	void setClock(LongSupplier now)
	{
		clock = now == null ? System::currentTimeMillis : now;
		renderValue();
	}

	/**
	 * Puts the right lines into the card and the right figures onto the move line (O3): the caption row, the
	 * strip, the footnote and the update line always; the total while {@code value} is on; the move line while
	 * {@code gp} or {@code pct} is - carrying the triangle, then the gp figure while {@code gp} is on, then the
	 * percentage while {@code pct} is. Nothing prints a placeholder for a hidden figure: the card simply shrinks.
	 */
	private void syncHero()
	{
		// The total's LINE is always in the card - the gear lives on it (Q1) - and the figure comes and goes
		// inside it. So the card shrinks by the 28 px figure when the total is switched off, not by the line.
		final List<Component> lines = new ArrayList<>(6);
		lines.add(captionRow);
		lines.add(totalRow);
		if (heroVisibility.moveLine())
		{
			lines.add(moveLine);
		}
		lines.add(stripHolder);
		lines.add(footnoteLabel);
		// S1: last, under the footnote and before the card's bottom padding, in every state - the figures can be
		// switched off, the sentence explaining why they sit still cannot.
		lines.add(updateLabel);
		replaceChildren(hero, lines);
		// Not through replaceChildren: that adds by INDEX, and an index-added child of a BorderLayout lands in
		// the centre - which would put the total where the layout has no constraint for it. One child, one
		// constraint, added and removed like the EMPTY card's button.
		final boolean wantTotal = heroVisibility.value();
		if (wantTotal != (totalLabel.getParent() == totalRow))
		{
			if (wantTotal)
			{
				totalRow.add(totalLabel, BorderLayout.WEST);
			}
			else
			{
				totalRow.remove(totalLabel);
			}
			totalRow.revalidate();
			totalRow.repaint();
		}

		final List<Component> parts = new ArrayList<>(3);
		parts.add(triangleLabel);
		if (heroVisibility.gp())
		{
			parts.add(deltaLabel);
		}
		if (heroVisibility.pct())
		{
			parts.add(pctLabel);
		}
		replaceChildren(moveLine, parts);
	}

	/** Ticks the eight menu items to the switches; {@code setSelected} fires no action, so nothing is written. */
	private void syncHeroMenu()
	{
		showValueItem.setSelected(heroVisibility.value());
		showGpItem.setSelected(heroVisibility.gp());
		showPctItem.setSelected(heroVisibility.pct());
		livePricesItem.setSelected(options.livePrices());
		countCashItem.setSelected(options.countCash());
		countUntradeablesItem.setSelected(options.countUntradeables());
		countInventoryItem.setSelected(options.countInventory());
		// AH: not a check item, so it is the ICON that carries the state - redrawn here rather than toggled,
		// which is what keeps it right when the switch is changed from RuneLite's settings page.
		showHoverTextItem.setIcon(Widgets.checkBox(options.showHoverText()));
	}

	// ---------------------------------------------------------------- the view switches (EDT, Q1-Q6)

	/**
	 * The view switches changed under us - the plugin's {@code ConfigChanged} for one of the five keys, or the
	 * gear menu through {@link #setOptions} - so the panel draws them: the gear menu's ticks, the card (its
	 * tooltip names untradeables only while they are counted, Q5; its last line and that line's hover follow the
	 * live switch, T5; its first line says the total includes what the player carries while {@code countInventory}
	 * is on, Y3) and, when {@code showHoverText} moved, every hover this panel owns (AH3).
	 * Saves nothing back; null reads as {@link ViewOptions#DEFAULT}; the same value again is
	 * a tick and a repaint and no rebuild.
	 *
	 * <p><b>The control row is no longer among them</b> (addendum X, line X2). Until X the sort button's hover
	 * named the figure the gp column compares, so this had to repaint it whenever the row switch moved
	 * or the hover would outlive its reading; the hover is now {@link #SORT_BUTTON_TIP} under every switch, and
	 * the menu never said anything about the switch (W3) and is rebuilt on each open besides. Nothing in the
	 * control row reads {@link #options} any more, so nothing repaints it here.
	 *
	 * <p><b>And the ROWS are no longer among them either</b> (addendum AO). Until AO one switch,
	 * {@code holdingOnRows}, was a pure reading of rows already published - the stack's figures instead of the
	 * item's - so this method rebuilt the open page whenever it moved (Q6). Addendum AN's three-line row prints
	 * BOTH readings at once, the key is deleted, and nothing left in {@link ViewOptions} changes a row without
	 * the service republishing it.
	 *
	 * <p><b>The live switch does not rebuild the rows here</b> (T1): it changes what
	 * the SERVICE publishes - every liquid row's price, baseline and move - so the rows follow on the recompute
	 * that the same switch starts, and a rebuild here would only throw away a page that is about to be replaced.
	 * What changes at once is the card, which is the part of the sidebar that says which series is on.
	 *
	 * <p><b>Nor does the carried switch</b> (Y1, the same rule). {@code countInventory} decides which STACKS the
	 * service merges and what each one's quantity is, so the whole list is recomputed and republished by the value
	 * the plugin hands {@code PriceService.setOptions} - rows, their split and the tooltip line that names it
	 * (Y3) arrive together.
	 *
	 * <p><b>What this does NOT do is decide the list.</b> Which stacks are listed and what they are worth is the
	 * service's answer to the same switches ({@code PriceService.setOptions}, Q4/Q5): the plugin hands them to
	 * both, and the recompute arrives here as an ordinary publish. So a switch that changes the figures repaints
	 * twice - once now, with what is on screen, and once when the service has caught up - and never shows a row
	 * drawn under one reading beside a total computed under another for longer than that.
	 */



	/**
	 * Gives {@code c} the hover it carries while the switch is on, and shows it only if it is (AH3).
	 *
	 * <p>The ONE road every tooltip in this panel takes. An empty or null text un-registers the component
	 * rather than storing a blank, because Swing opens an empty grey box for "" and a component with nothing
	 * to say must have nothing set.
	 */
	private void setHover(@Nullable JComponent c, @Nullable String text)
	{
		if (c == null)
		{
			return;
		}
		if (text == null || text.isEmpty())
		{
			hoverTexts.remove(c);
			c.setToolTipText(null);
			return;
		}
		hoverTexts.put(c, text);
		c.setToolTipText(options.showHoverText() ? text : null);
	}

	/**
	 * Adopts the full-text hover {@link Widgets#setFitted} leaves on a label it had to cut (AH3).
	 *
	 * <p>That hover is the reason the first cut of AH still showed text with the switch off: it is set by the
	 * fitting helper rather than by this class, on the total, the footnote, the band target and the problem
	 * line, and it appears exactly when a reader is most likely to go looking for it - when the label is too
	 * narrow to read. Called immediately after each fit, so the text is read back and re-applied under the
	 * switch.
	 */
	private void adoptFittedHover(JComponent c)
	{
		setHover(c, c.getToolTipText());
	}

	/** Re-applies every registered hover to the switch's state - the flip, in one pass (AH3). */
	private void applyHoverSwitch()
	{
		final boolean on = options.showHoverText();
		for (Map.Entry<JComponent, String> e : hoverTexts.entrySet())
		{
			// Straight to Swing, deliberately NOT through setHover: that road un-registers a component given
			// no text, so turning the switch off would empty this map and turning it back on would restore
			// nothing. What is registered is what the component says when the switch is on, and it stands
			// whatever the switch currently is.
			e.getKey().setToolTipText(on ? e.getValue() : null);
		}
	}

	public void applyOptions(@Nullable ViewOptions next)
	{
		if (stopped)
		{
			return;
		}
		final ViewOptions want = next == null ? ViewOptions.DEFAULT : next;
		final boolean hoverChange = want.showHoverText() != options.showHoverText();
		if (!want.equals(options))
		{
			// AS: a switch that changes what the figures MEAN is answered by the service's recompute, and a reader
			// who flips one while banking is shown that answer rather than a list computed under the old switch.
			liftBankHold();
		}
		options = want;
		syncHeroMenu();
		if (hoverChange)
		{
			// AH3: every hover this panel owns, in one pass. The ROWS are not rebuilt for it - since addendum
			// AI a row carries no tooltip at any setting, because its description is the block the cell opens.
			applyHoverSwitch();
		}
		// AO: no switch left here rebuilds the open page. The one that did (holdingOnRows) is deleted, and every
		// other one reaches the rows through the service's own recompute and arrives as an ordinary publish.
		renderValue();
	}

	/**
	 * The gear menu's own switch (Q2): applies at once AND writes the choice through {@link Prefs#saveOptions},
	 * so the stored config follows - the round trip {@link #setHeroVisibility} makes for the card's three.
	 * Nothing is written when nothing changed.
	 */
	public void setOptions(@Nullable ViewOptions next)
	{
		final ViewOptions want = next == null ? ViewOptions.DEFAULT : next;
		final boolean changed = !want.equals(options);
		applyOptions(want);
		if (changed && !updating)
		{
			prefs.saveOptions(want);
		}
	}

	/** The five view switches the panel is drawing right now (Q3, T1, Y1, AH); never null. */
	public ViewOptions options()
	{
		return options;
	}

	// ---------------------------------------------------------------- the price presets (EDT, Z1-Z3)

	/**
	 * The quick bands changed under us - the plugin's {@code ConfigChanged} for {@code bandPresets}, or the gear
	 * menu's boxes through {@link #setPresets} - so the panel draws them: the three boxes re-print their bands in
	 * ascending order and lose any red, and the fold's chips take their new labels, hovers and bands (Z3).
	 *
	 * <p>It saves nothing back and never touches the FILTER: the reader's Min / Max band is theirs, and a preset
	 * is only the one-tap way to set one. The chips are repainted because the LIT one can move without the band
	 * moving at all - a bank on "1m and up" is on a preset while the presets are 100k / 1m / 10m and on a custom
	 * band the moment they become 500k / 10m / 100m.
	 *
	 * <p>Null reads as {@link BandPresets#DEFAULT}; the same value again is a repaint and nothing else. The
	 * service is not told, because no figure this panel shows depends on which bands the fold offers.
	 */
	public void applyPresets(@Nullable BandPresets next)
	{
		if (stopped)
		{
			return;
		}
		presets = next == null ? BandPresets.DEFAULT : next;
		renderPresetFields();
		renderPresetCells();
		renderChips();
	}

	/**
	 * The gear menu's own change (Z2: the boxes, or "Reset to default") and the bridge's {@code presets=}:
	 * applies at once AND writes the three through {@link Prefs#savePresets}, so the stored config follows - the
	 * round trip {@link #setOptions} makes for the view switches. Nothing is written when nothing changed, and
	 * the boxes still re-print, which is what puts a refused edit's red away when the reader asks for what is
	 * already in force.
	 */
	public void setPresets(@Nullable BandPresets next)
	{
		final BandPresets want = next == null ? BandPresets.DEFAULT : next;
		final boolean changed = !want.equals(presets);
		applyPresets(want);
		if (changed && !updating)
		{
			prefs.savePresets(want);
		}
	}

	/** The three quick bands the fold is offering right now (Z1); never null. */
	public BandPresets presets()
	{
		return presets;
	}

	/**
	 * Reads the three boxes TOGETHER (Z2) - on Enter, on focus lost and when the menu closes - and, when they
	 * name three distinct positive amounts, sorts them and makes them the presets through {@link #setPresets}.
	 *
	 * <p>They are one value, so they are judged as one: a box whose text is not a positive gp amount goes red,
	 * and so do BOTH boxes of a pair naming the same amount - the two failures a reader can make here, each shown
	 * where they made it. Nothing is saved while any box is red; the presets in force stay, which is what keeps a
	 * half-typed trio from ever reaching the fold. The fold's own Min / Max fields judge themselves one at a time
	 * for the same reason in reverse: either bound is a band on its own.
	 */
	private void commitPresets()
	{
		if (stopped)
		{
			return;
		}
		final long[] values = new long[presetFields.length];
		final boolean[] bad = new boolean[presetFields.length];
		boolean ok = true;
		for (int i = 0; i < presetFields.length; i++)
		{
			long value;
			try
			{
				value = MovementMath.parseGp(presetFields[i].getText().trim());
			}
			catch (ParseException e)
			{
				value = 0L;
			}
			// A band of nothing is not a band: "All" is already the fold's first chip, so 0 is as unreadable
			// here as "abc" is.
			values[i] = value;
			if (value <= 0L)
			{
				bad[i] = true;
				ok = false;
			}
		}
		for (int i = 0; i < values.length; i++)
		{
			for (int j = i + 1; j < values.length; j++)
			{
				if (values[i] > 0L && values[i] == values[j])
				{
					bad[i] = true;
					bad[j] = true;
					ok = false;
				}
			}
		}
		for (int i = 0; i < presetFields.length; i++)
		{
			Widgets.markInvalid(presetFields[i], bad[i]);
		}
		if (!ok)
		{
			return;
		}
		final BandPresets next;
		try
		{
			next = BandPresets.of(values[0], values[1], values[2]);
		}
		catch (IllegalArgumentException refused)
		{
			// The invariant itself lives in BandPresets; the two loops above are this row's reading of it, and if
			// the two ever part company the boxes say so rather than throwing out of an action listener.
			for (Widgets.PlaceholderField field : presetFields)
			{
				Widgets.markInvalid(field, true);
			}
			return;
		}
		setPresets(next);
	}

	/**
	 * Spells the three boxes from the presets, smallest first, and clears their red (Z2). A box already holding
	 * that spelling is left alone, so re-printing does not move the caret of the one being typed in.
	 *
	 * <p>The spelling is {@link MovementMath#formatGp} - what {@link BandPresets#format()} writes into the config
	 * and what the chips wear - rather than whatever the reader typed. A band is stored as shorthand, so a box
	 * that went on showing "1234567" beside a config line reading "1.23m" would be showing a number the plugin no
	 * longer holds; the rounding is the price of the shorthand and is better seen at once than found later.
	 */
	private void renderPresetFields()
	{
		final long[] mins = presets.mins();
		for (int i = 0; i < presetFields.length; i++)
		{
			final String text = MovementMath.formatGp(mins[i]);
			if (!text.equals(presetFields[i].getText()))
			{
				presetFields[i].setText(text);
			}
			Widgets.markInvalid(presetFields[i], false);
		}
	}

	/** The fold's four chips, labelled and explained from the presets (Z3); what each one DOES is its index. */
	private void renderPresetCells()
	{
		for (int i = 0; i < presetCells.length; i++)
		{
			final long min = presetMin(i);
			presetCells[i].setText(presetLabel(i));
			setHover(presetCells[i], min == 0L ? "Every item, whatever its price"
				: "Items priced " + MovementMath.formatGp(min) + " and up");
		}
	}

	/** Chip {@code i}'s lower bound: none for "All", then the three presets, smallest first (Z3). */
	long presetMin(int i)
	{
		return i == 0 ? 0L : presets.mins()[i - 1];
	}

	/** Chip {@code i}'s text: {@link #ALL_LABEL}, then {@link BandPresets#labels()} (Z3). */
	String presetLabel(int i)
	{
		return i == 0 ? ALL_LABEL : presets.labels()[i - 1];
	}

	/** The fold's four chip texts in order - {@code describe()}'s {@code presetLabels} (Z4). */
	public String[] presetLabels()
	{
		final String[] out = new String[PRESET_COUNT];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = presetLabel(i);
		}
		return out;
	}

	// ---------------------------------------------------------------- the filter (EDT)

	/**
	 * The config changed under us (the plugin's {@code ConfigChanged}, on the EDT): repaint the widgets from
	 * {@code next} WITHOUT saving it back - the plugin hands the same filter to the service itself.
	 */
	public void applyFilter(@Nullable RowFilter next)
	{
		// Both config paths post their repaint with invokeLater (the plugin's onConfigChanged), so one can be
		// drained after shutDown has already removed this panel from the toolbar and dropped its listener; the
		// same guard onRows, refreshNow and showMore carry (contract C33).
		if (stopped)
		{
			return;
		}
		updating = true;
		try
		{
			final RowFilter old = filter;
			filter = next == null ? RowFilter.DEFAULT : next;
			if (!filter.equals(old))
			{
				// AS: the settings page changed the list - the plugin hands the same filter to the service - so its
				// publish is drawn even while the bank is open, or the list would sit under controls that say
				// otherwise until the bank closed. The panel's own write echoing back is equal and lifts nothing.
				liftBankHold();
			}
			renderChips();
			renderBounds();
			renderValue();
			renderControl();
		}
		finally
		{
			updating = false;
		}
	}

	public RowFilter filter()
	{
		return filter;
	}

	/** A window chip: the same code path as the click, for the bridge's {@code window=} verb. */
	public void selectWindow(@Nullable MovementWindow window)
	{
		if (window != null)
		{
			changeFilter(filter.withWindow(window));
		}
	}

	/**
	 * PRESSING a column - one entry of the sort menu, and the bridge's {@code sort=} verb (addendum W, lines W3
	 * and W4): the column already lit FLIPS the direction, and another column lights BIGGEST FIRST whatever
	 * direction the one before it had.
	 *
	 * <p>The flip half is the old chip gesture (contract C29), and it is what the arrow now makes visible. The
	 * other half changed with W: before it kept the direction, so choosing "Item price" while the list was on
	 * biggest losers answered with the CHEAPEST items in the bank and nothing on screen said why. A new column
	 * is a new question, and the interesting end of every one of these four is the big end - so it starts there,
	 * and one more press is the whole cost of the other end.
	 *
	 * @param sort the column to press; null does nothing (the bridge passes an unparsed word straight through)
	 */
	public void clickSort(@Nullable SortMode sort)
	{
		if (sort == null)
		{
			return;
		}
		changeFilter(sort == filter.sort() ? filter.withDescending(!filter.descending())
			: filter.withSort(sort).withDescending(true));
	}

	/** The bridge's {@code dir=asc|desc}: the direction alone. */
	public void setDescending(boolean descending)
	{
		changeFilter(filter.withDescending(descending));
	}

	/**
	 * The column and the direction in ONE filter change, for a caller that knows both - the config road and the
	 * tests. A menu entry does NOT come this way: picking one is a PRESS ({@link #clickSort}), which is the only
	 * road that can flip.
	 *
	 * @param sort       the column; null reads as {@link SortMode#PERCENT_MOVE}, matching {@link RowFilter}'s own
	 *                   null handling
	 * @param descending true for biggest first
	 */
	public void setSort(@Nullable SortMode sort, boolean descending)
	{
		changeFilter(filter.withSort(sort == null ? SortMode.PERCENT_MOVE : sort).withDescending(descending));
	}

	/**
	 * Types {@code text} into the Min field and applies it exactly as Enter would (the bridge's {@code min=}),
	 * and opens the fold so a shot shows what happened (N 3.5).
	 *
	 * @return false when the text did not parse (the field is red and the filter unchanged)
	 */
	public boolean applyMin(@Nullable String text)
	{
		minField.setText(text == null ? "" : text);
		final boolean ok = applyBound(minField, true);
		setFoldOpen(true);
		return ok;
	}

	/** {@link #applyMin} for the Max field. */
	public boolean applyMax(@Nullable String text)
	{
		maxField.setText(text == null ? "" : text);
		final boolean ok = applyBound(maxField, false);
		setFoldOpen(true);
		return ok;
	}

	/**
	 * A preset, the fold's "x" or the EMPTY card's "Clear price range" (N 3.5): both bounds in one filter
	 * change, and the fields follow - the one place a click rewrites them.
	 */
	public void applyBand(long min, long max)
	{
		changeFilter(filter.withGpMin(Math.max(0L, min)).withGpMax(Math.max(0L, max)));
		renderBounds();
	}

	/**
	 * The Refresh LINK - and the bridge's {@code refresh}, which presses it: ONE act wherever the player is standing,
	 * since AS8 (2026-09-23). Addendum AS had made it one of two ({@code docs/handoff/plan-AS-bank-hold-2026-09-21.md}
	 * sections 1 and 2.3) - the local update with the bank open, the price re-check with it shut - and the user, on the
	 * AS7 build, clicked it with the bank open and saw nothing move, clicked it with the bank shut and saw the prices
	 * move, and asked why: "manually clicking the refresh button should refresh everything for the user". So every
	 * click refreshes both halves, the items and the prices.
	 *
	 * <p><b>With the bank open</b> and the plugin's hook registered ({@link #setBankRefresh}, read through
	 * {@link #openBankRefresh}) the items come first, and at once: the hook re-reads the bank on the client thread and
	 * republishes it - the local update the glow invites, no download. Three things happen here, on the click itself:
	 * the ring goes out ({@link #glowDismissed} - the click is the answer to it, so it does not wait a frame and a disk
	 * write for the plugin to report the read), the hold is lifted so the answer is DRAWN while the bank stays open
	 * ({@link #liftBankHold} - without it the re-read's publish would be stored like any other and the click would
	 * change nothing on screen), and the link acknowledges the tap with the same two beats a price check gets - once,
	 * for the click, and not again for its second half. THEN the prices: {@code service.refreshNow(true)}, the price
	 * re-check with the 30 s cooldown applying to the PRICE part alone, its fetches running in the background on the
	 * service's own executor. Quiet, because the items did refresh: a click inside the cooldown has still re-read the
	 * bank and redrawn the list, and the red "Refreshed n s ago - wait" line under it would say that nothing happened.
	 * The service refuses the download without a word and arms no clear for a line it never showed.
	 *
	 * <p><b>Otherwise</b> - the bank shut, or no hook - it is {@link #refreshPricesNow()}, exactly as before AS8. With
	 * the bank shut the stored bank is already the one on screen, and the price re-check's own re-read of what the
	 * player carries (the service's carried reader, addendum Y) covers the rest of the items; inside the cooldown
	 * NOTHING refreshed, so there the wait line is the true answer, and it stays.
	 */
	public void refreshNow()
	{
		if (stopped)
		{
			return;
		}
		glowDismissed = true;
		stopGlow();
		final Runnable hook = openBankRefresh();
		if (hook != null)
		{
			// Lifted before the hook runs, so an answer that came back at once would still be drawn.
			liftBankHold();
			hook.run();
			startRefreshAck();
			// AS8: then the prices, in the background - quiet, because the items above did refresh, so a cooldown
			// refusal must not put "wait" under a list this click has just redrawn.
			service.refreshNow(true);
			return;
		}
		refreshPricesNow();
	}

	/**
	 * The price re-check ALONE: the gear menu's "Refresh prices now" always (addendum AS, section 7.1 decision 2 - its
	 * name says PRICES, and AS8 kept it so), and the whole of the link's click whenever there is no open bank to
	 * re-read - the bank shut, or no hook ({@link #refreshNow}). The service is told out loud
	 * ({@code refreshNow(false)}): it decides about the 30 s cooldown and says so in the problem row, because here
	 * nothing else was refreshed and a refusal is the whole answer to the tap.
	 *
	 * <p>The link answers the tap in two beats (P2): "Refreshing..." for {@link #REFRESH_ACK_MILLIS}, then
	 * <b>"Up to date"</b> for {@link #UP_TO_DATE_MILLIS}, then "Refresh" again. Without the first beat the
	 * ACCEPTED path is silent - the service publishes no "I started", and guide prices change once a day, so the
	 * figures usually come back identical - and the only answer the control ever gave was the RED cooldown line
	 * earned by tapping it a second time because the first tap looked dead. The second beat is what says the work
	 * FINISHED rather than merely started, and it fades on its own so the card goes back to its minimal face
	 * (the user's own wish). Both are held by this panel's own timers rather than cleared by the next publish,
	 * because the publish that follows a refresh lands within a frame or two of the click and would wipe them
	 * before they could be read. They are words on the control that was tapped, not sentences in the problem row,
	 * so they cannot fight the cooldown line for that row.
	 *
	 * <p><b>A refused tap leaves the link alone</b> in the only sense this panel can honour: nothing different is
	 * painted on it. {@link PriceService#refreshNow(boolean)} returns void and answers a refusal by publishing the red
	 * "Refreshed 12 s ago - wait" line into the problem row (K5/L10), so the panel is never told which tap was
	 * served - the link answers the GESTURE and the problem row answers the OUTCOME. A refused tap is one made
	 * inside 30 s of the last, so the prices really are up to date and the word is true either way.
	 *
	 * <p><b>With the bank open</b> (addendum AS) two more things hold. The reader asked, so the hold is lifted and
	 * whatever the re-check publishes - the cooldown line, the new prices - is drawn ({@link #liftBankHold}). And while
	 * the ring is lit the beats are NOT put on the link: a price check leaves the bank as out of date as it was,
	 * so the glow stays, and "the link's text stays 'Refresh' while glowing" (section 7.3) - "Up to date" on a link
	 * that is glowing because the list is not up to date would be the one sentence on the card that is false. A
	 * refusal still answers in the problem row.
	 */
	public void refreshPricesNow()
	{
		if (stopped)
		{
			return;
		}
		// Before the service is told, so an answer that came back at once would still be drawn.
		liftBankHold();
		if (!glowRunning())
		{
			startRefreshAck();
		}
		// Out loud (AS8's false): nothing else refreshed here, so a cooldown refusal is the answer, and says so.
		service.refreshNow(false);
	}

	/**
	 * What the Refresh link is saying, and for how long (P2). The link's sequence IS this enum: a tap starts at
	 * {@link #ACKNOWLEDGING}, its timer moves it to {@link #UP_TO_DATE}, and that one's timer ends at
	 * {@link #IDLE} - so the word on screen is derived from the phase
	 * ({@link BankPriceMovementPanel#renderRefreshLink}) instead of being read back out of the label, and one
	 * timer carries all of it at the delay the phase names.
	 */
	private enum RefreshPhase
	{
		/** Nothing in flight: the control's resting word. */
		IDLE(0, REFRESH_TEXT),
		/** Beat one: the tap has been taken. */
		ACKNOWLEDGING(REFRESH_ACK_MILLIS, REFRESHING_TEXT),
		/** Beat two: the work finished, and this fades on its own. */
		UP_TO_DATE(UP_TO_DATE_MILLIS, UP_TO_DATE_TEXT);

		/** How long this beat stands before the next one; 0 for {@link #IDLE}, which waits for a tap instead. */
		private final int millis;
		private final String text;

		RefreshPhase(int millis, String text)
		{
			this.millis = millis;
			this.text = text;
		}
	}

	/**
	 * Puts "Refreshing..." on the caption row's link and starts the sequence again from the top - so a second tap
	 * during either beat restarts it rather than shortening it.
	 */
	private void startRefreshAck()
	{
		setRefreshPhase(RefreshPhase.ACKNOWLEDGING);
	}

	/**
	 * The acknowledgement timer's action: "Refreshing..." becomes "Up to date" and the fade timer starts (P2).
	 * Package-private so a test can drive the sequence without waiting {@link #REFRESH_ACK_MILLIS} of real time.
	 */
	void fireRefreshAck()
	{
		setRefreshPhase(stopped ? RefreshPhase.IDLE : RefreshPhase.UP_TO_DATE);
	}

	/**
	 * The fade timer's action: "Up to date" goes back to "Refresh" (P2). Package-private for the same reason as
	 * {@link #fireRefreshAck()} - a minute is not a thing to wait for in a test.
	 */
	void fireUpToDate()
	{
		clearRefreshAck();
	}

	/**
	 * Puts "Refresh" back and stops the timer; safe at any time, and called from {@link #stop()} so a panel the
	 * client has removed leaves nothing ticking (P2).
	 */
	void clearRefreshAck()
	{
		setRefreshPhase(RefreshPhase.IDLE);
	}

	/**
	 * Enters a beat: the word goes on the link, the one timer is restarted at that beat's delay - or stopped, at
	 * {@link RefreshPhase#IDLE}, which is where a dead panel and a faded link both end.
	 */
	private void setRefreshPhase(RefreshPhase phase)
	{
		refreshPhase = phase;
		renderRefreshLink();
		if (refreshTimer != null)
		{
			refreshTimer.stop();
		}
		if (phase.millis <= 0)
		{
			return;
		}
		if (refreshTimer == null)
		{
			refreshTimer = new Timer(phase.millis, e -> fireRefreshTimer());
			refreshTimer.setRepeats(false);
		}
		refreshTimer.setInitialDelay(phase.millis);
		refreshTimer.setDelay(phase.millis);
		refreshTimer.restart();
	}

	/** The timer's action, dispatched on the beat that armed it: one beat ends where the next begins. */
	private void fireRefreshTimer()
	{
		if (refreshPhase == RefreshPhase.ACKNOWLEDGING)
		{
			fireRefreshAck();
		}
		else if (refreshPhase == RefreshPhase.UP_TO_DATE)
		{
			fireUpToDate();
		}
	}

	/** The link's word, from the phase alone. */
	private void renderRefreshLink()
	{
		refreshLabel.setText(refreshPhase.text);
	}

	/** Whether the caption row's link is acknowledging a tap right now ("Refreshing...", the first beat). */
	boolean refreshAcknowledging()
	{
		return refreshPhase == RefreshPhase.ACKNOWLEDGING;
	}

	/** Whether the link is standing on "Up to date" - the second beat, waiting for its minute (P2). */
	boolean upToDateShowing()
	{
		return refreshPhase == RefreshPhase.UP_TO_DATE;
	}

	/**
	 * Whether any of the link's timers is still running - a beat's, or the glow's since addendum AS - the tests' proof
	 * that {@link #stop()} left nothing ticking.
	 */
	boolean refreshTimersRunning()
	{
		return (refreshTimer != null && refreshTimer.isRunning()) || glowRunning();
	}

	// ---------------------------------------------------------------- the bank hold and the glow (EDT, addendum AS)

	/**
	 * The plugin's word about the bank (addendum AS, section 7.3), posted to the EDT whenever one of the four changes:
	 * whether the bank interface is open, whether the bank on screen is out of date (a read is owed), how many bank
	 * events it has held this visit, and how many reads it has made. The panel MIRRORS them - it cannot see the
	 * widget, and a second opinion would be a second answer - and acts on them in three ways.
	 *
	 * <p><b>The hold.</b> While the bank is open {@link #onRows} stores a publish instead of building it, exactly as it
	 * does while the sidebar is hidden (section 7.2: one rule, not two); when the bank closes the stored one is
	 * replayed, once, through {@link #releaseHeld} - the road {@link #onActivate} takes too, so a replay has one
	 * spelling and a panel still hidden when the bank closes keeps it for the next show. A new visit, and a change the
	 * plugin reports mid-visit, put back a hold that had been lifted ({@link #liftBankHold}): the lift answered a click
	 * or a read, and the bank has moved on since.
	 *
	 * <p><b>The glow.</b> Started and stopped by {@link #syncGlow} - running only while the sidebar shows the link,
	 * the bank is open AND a change is owed.
	 *
	 * <p><b>The click.</b> Whatever the Refresh link's click dismissed ({@link #glowDismissed}), the plugin speaking
	 * again decides afresh: its word is newer than the click.
	 *
	 * <p><b>Not the hover.</b> Whether the bank is open still decides whether a click on the link runs the plugin's
	 * re-read before its price re-check ({@link #refreshNow}), but no longer what the link's hover says: since AS8 a
	 * click refreshes everything in every state, so one sentence describes it in every state ({@link #REFRESH_TIP}).
	 * Addendum AS's F5 put the hover back in step on every word here, while a click meant one of two things.
	 *
	 * <p>Posted back to the EDT when called from anywhere else - {@link #onRows}' rule - because the glow's timer, the
	 * link and the stored publish are all the EDT's. A stopped panel ignores it: the plugin posts with
	 * {@code invokeLater}, so the last word can land after {@code shutDown}, and a dead panel must not start a timer.
	 */
	public void setBankHold(boolean open, boolean pending, int heldEvents, int reads)
	{
		if (stopped)
		{
			return;
		}
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> setBankHold(open, pending, heldEvents, reads));
			return;
		}
		final boolean newVisit = open && !bankOpen;
		final boolean newChange = open && pending && !bankPending;
		bankOpen = open;
		bankPending = pending;
		bankHeldEvents = heldEvents;
		bankReads = reads;
		glowDismissed = false;
		if (!open || newVisit || newChange)
		{
			bankHoldLifted = false;
		}
		syncGlow();
		if (!open)
		{
			// The replay section 7.2 asks for: "when open goes false". Only then - with the bank still open, a hold the
			// reader lifted is answered by the publish their act causes, which supersedes whatever is stored; replaying
			// the stored one here as well would draw a stale picture a frame before the right one.
			releaseHeld();
		}
	}

	/**
	 * The plugin's open-bank Refresh (section 7.3): what the link's click runs FIRST while the bank is open - the
	 * items, re-read at once - before the price re-check it runs in every state ({@link #refreshNow}, AS8). Null
	 * takes it away - {@code shutDown} does - and the click is then the price re-check alone, whatever the bank is
	 * doing. A stopped panel keeps no hook, so a late registration cannot pin the plugin that made it.
	 *
	 * <p>Callable from any thread: it writes one volatile field and touches nothing Swing, so the hook is taken at once
	 * - a hook taken away is not run by the next click, whichever thread took it. Nothing else follows the hook any
	 * more: addendum AS's F5 put the link's hover in step with it, on the EDT, because the hook decided which of two
	 * things a click would be; since AS8 the click refreshes everything with or without it, and the hover is one
	 * sentence in every state.
	 */
	public void setBankRefresh(@Nullable Runnable refresh)
	{
		bankRefresh = stopped ? null : refresh;
	}

	/**
	 * What a click on the Refresh link runs BEFORE its price re-check: the plugin's hook while the bank is open, and
	 * null otherwise - the bank shut, or no hook registered (the headless renderer, a test, the moment between
	 * {@code shutDown}'s {@code setBankRefresh(null)} and {@link #stop()}). Read by the click alone
	 * ({@link #refreshNow}); until AS8 the link's hover read it too, because it decided which of two things a click
	 * would be. The volatile field is read once, so the answer is one hook and not two reads of it.
	 */
	@Nullable
	private Runnable openBankRefresh()
	{
		final Runnable hook = bankRefresh;
		return bankOpen ? hook : null;
	}

	/**
	 * Whether a publish arriving now is STORED rather than built: while the sidebar is hidden (design D8), and while
	 * the bank is open unless the hold has been lifted - by the reader ({@link #liftBankHold}) or by a bank the
	 * sidebar has not drawn ({@link #carriesNewBank}).
	 */
	private boolean holding()
	{
		return !active || (bankOpen && !bankHoldLifted);
	}

	/**
	 * Whether {@code candidate} carries a bank this sidebar has not drawn: another capture than the one on screen
	 * ({@code Status.bankAtMillis()} is the snapshot's capture stamp, which {@code BankReader} sets to the clock at
	 * every read and {@code BankSnapshot.withCarried} keeps), or any bank at all while nothing has been drawn yet.
	 *
	 * <p>Such a publish is a READ, and the hold is for re-statements - the half-hourly tick, a fetch landing - so
	 * {@link #onRows} lifts the hold for it. By the plan the plugin reads with the bank open only when the reader
	 * asks - the link's click (section 2.3) - or before it knows the bank is open: the bank's first event of a visit
	 * beating the widget's own load event, an ORDER section 7.2 lists as unverified, or the plugin switched on at an
	 * open bank. The reader must see every one of them. Held, such a read would leave the old bank on screen for the
	 * whole visit with no glow to say so, since the plugin, having read, owes no change; and a player who turns the
	 * plugin on, or installs it, at an open bank would read "Open your bank once to load your items" over the bank
	 * they have open. Lifted rather than
	 * let through once, for {@link #liftBankHold}'s reason: a status-only publish can land between a read and its
	 * recompute, carrying the new stamp over the old rows, and the recompute must not then be stored.
	 */
	private boolean carriesNewBank(@Nullable Status candidate)
	{
		return candidate != null && (status == null || candidate.bankAtMillis() != status.bankAtMillis());
	}

	/**
	 * Builds the stored publish when nothing holds it any more, and does nothing otherwise - the ONE replay road:
	 * the sidebar coming back ({@link #onActivate}) and the bank closing ({@link #setBankHold}) both come here, so a
	 * publish stored for either reason is built exactly once, by whichever of them ends the hold, and never while
	 * the other still holds it.
	 */
	private void releaseHeld()
	{
		if (stopped || !pendingPublish || holding())
		{
			return;
		}
		final List<MovementRow> newRows = pendingRows;
		final Status newStatus = pendingStatus;
		pendingPublish = false;
		pendingRows = null;
		pendingStatus = null;
		onRows(newRows, newStatus);
	}

	/**
	 * The reader's own act while the bank is open - the Refresh link, a window, a column, a band, a view switch -
	 * lifts the hold: every publish is built again until the plugin reports a new change or the bank closes
	 * ({@link #setBankHold}).
	 *
	 * <p>Why the hold may not simply stand for the whole visit, as section 7.2 first drew it: each of those acts is
	 * ANSWERED by a publish - the service re-sorts, re-counts or re-prices, or the plugin re-reads the bank - and that
	 * answer necessarily arrives while the bank is still open. A hold that stored it would re-order nothing when a
	 * column was picked and draw nothing when the glowing link was clicked, under controls that already say the new
	 * thing. The lift cannot be spent on one publish either, because nothing ties a publish to the act that caused it:
	 * a status-only publish (a fetch landing, the cooldown line clearing) can arrive between the act and its answer,
	 * and spending the lift on that one would store the answer itself. So it lasts until the plugin says the bank
	 * moved on - which is also the moment the glow comes back - or the visit ends. The cost is an unasked-for rebuild
	 * for each re-statement that lands in between: the half-hourly tick, a fetch. While a change is already owed the
	 * plugin reports no new one, so a lift made then lasts until the link is clicked or the bank closes.
	 *
	 * <p>Nothing is replayed here. Every act that lifts is followed by the publish that answers it, which supersedes
	 * the stored one ({@link #onRows} drops it on building), and the stored one is still replayed when the bank closes
	 * should no answer ever come.
	 */
	private void liftBankHold()
	{
		if (bankOpen)
		{
			bankHoldLifted = true;
		}
	}

	/**
	 * Whether the ring should be lit right now (section 7.3): the sidebar showing the link, the bank open and a change
	 * owed - and no click on the link since the plugin last spoke.
	 */
	private boolean glowWanted()
	{
		return !stopped && active && bankOpen && bankPending && !glowDismissed;
	}

	/** Starts or stops the ring to match {@link #glowWanted()} - the one road every trigger takes. */
	private void syncGlow()
	{
		if (glowWanted())
		{
			startGlow();
		}
		else
		{
			stopGlow();
		}
	}

	/**
	 * Lights the ring: its breath starts now, from dark - {@link #glowLevel} reads 0 at this instant - and the timer
	 * runs at {@link #GLOW_TICK_MILLIS}. Nothing is repainted here: at 0 there is nothing to draw, and the frames draw
	 * the breath as it comes up.
	 *
	 * <p>A beat still standing on the link from an earlier tap - "Up to date", "Refreshing..." - is ended first,
	 * because "the link's text stays 'Refresh' while glowing" (section 7.3), and a new change makes "Up to date" the
	 * one thing on the card that is no longer true.
	 */
	private void startGlow()
	{
		if (glowRunning())
		{
			return;
		}
		if (refreshPhase != RefreshPhase.IDLE)
		{
			clearRefreshAck();
		}
		if (glowTimer == null)
		{
			glowTimer = new Timer(GLOW_TICK_MILLIS, e -> fireGlow());
			glowTimer.setRepeats(true);
		}
		glowStartedAtMillis = glowClock.getAsLong();
		glowTimer.start();
	}

	/** Puts the ring out: the timer stops, and one last repaint of its region takes its last frame off the card. */
	private void stopGlow()
	{
		if (!glowRunning())
		{
			return;
		}
		glowTimer.stop();
		repaintGlow();
	}

	/**
	 * The glow timer's action, one frame: the ring's region of the hero card is repainted ({@link #glowRegion}) - not
	 * the rest of the card, not the header, and nothing is laid out, so a frame costs the ~54 x 23 px round the link
	 * and no more (section 7.2's "repaint of the link's bounds only", as far as AS7's wider ring allows).
	 *
	 * <p>A frame moves nothing. How bright the ring is comes from the clock, read as the card paints it
	 * ({@link #glowLevel}), so a frame that arrives late - the EDT busy with a page of rows, say - draws the breath
	 * where the clock has got to, never where a count of frames would put it: a stalled second can delay a frame, but
	 * it cannot stretch a six-second breath to seven.
	 *
	 * <p>Package-private so a test can run a frame without waiting on a real timer, as {@link #fireRefreshAck()} is. A
	 * frame arriving when the ring is out - queued before {@link #stopGlow()}, or after {@link #stop()} - does nothing,
	 * so nothing is ever repainted for a ring that is not lit.
	 */
	void fireGlow()
	{
		if (stopped || !glowRunning())
		{
			return;
		}
		repaintGlow();
	}

	/** Asks Swing to repaint the ring's region of the hero card ({@link #glowRegion}), and nothing else. */
	private void repaintGlow()
	{
		hero.repaint(glowRegion());
	}

	/** Whether the ring round the link is lit right now: its timer is running (section 7.3). */
	boolean glowRunning()
	{
		return glowTimer != null && glowTimer.isRunning();
	}

	/**
	 * How bright the ring is right now, from 0 to 1 - its alpha: the {@link #breath} at the time since it lit, by
	 * {@link #glowClock}; and 0 whenever it is out. Read by the card each time it paints, which is what makes the
	 * breath the clock's and not the frames' ({@link #fireGlow}).
	 */
	double glowLevel()
	{
		return glowRunning() ? breath(glowClock.getAsLong() - glowStartedAtMillis) : 0d;
	}

	/**
	 * The breath at {@code elapsedMillis} into it: {@code GLOW_FLOOR + (1 - GLOW_FLOOR) * (1 - cos(2 pi t /
	 * GLOW_BREATH_MILLIS)) / 2} - the floor (0.15) at the start, 1 at 3 s, the floor again at 6 s, and round again. The curve is the one P1 was rendered and chosen with, stretched to the
	 * user's six seconds: a cosine, which eases into full brightness and into nothing rather than turning there.
	 *
	 * <p>The time is taken round one breath before the cosine, so its argument stays inside one turn however long the
	 * ring stays lit, and a time before the start - a clock put back - reads as a phase like any other.
	 */
	static double breath(long elapsedMillis)
	{
		final long t = Math.floorMod(elapsedMillis, (long) GLOW_BREATH_MILLIS);
		return GLOW_FLOOR + (1d - GLOW_FLOOR) * (1d - Math.cos(2d * Math.PI * t / GLOW_BREATH_MILLIS)) / 2d;
	}

	/**
	 * Pins the clock the breath is timed by, for the tests, which drive a whole breath without waiting for one; null
	 * puts the monotonic clock back. Set it BEFORE the ring lights: the breath's start is stamped by the clock in force
	 * at that moment ({@link #startGlow}).
	 */
	void setGlowClock(@Nullable LongSupplier now)
	{
		glowClock = now == null ? BankPriceMovementPanel::monotonicMillis : now;
	}

	/** Milliseconds off {@link System#nanoTime}: a clock that only moves forward, for {@link #glowClock}. */
	private static long monotonicMillis()
	{
		return System.nanoTime() / 1_000_000L;
	}

	/** The ring's timer, or null before it first ran - for the tests that check its period and that it repeats. */
	@Nullable
	Timer glowTimer()
	{
		return glowTimer;
	}

	/**
	 * The box the ring runs round, in the hero card's coordinates: the Refresh link's bounds,
	 * {@link #GLOW_RING_EXTRA_RIGHT} px wider on the right. Read from where the link is NOW, so the ring follows it
	 * wherever the card's layout puts it.
	 */
	private Rectangle glowBox()
	{
		final Rectangle box = SwingUtilities.convertRectangle(refreshLabel.getParent(), refreshLabel.getBounds(), hero);
		box.width += GLOW_RING_EXTRA_RIGHT;
		return box;
	}

	/**
	 * What one frame repaints: the ring's box grown by the halo's reach on every side ({@link #GLOW_HALO_REACH}), in
	 * the hero card's coordinates - the whole of what {@link #paintRing} can draw there, and nothing more.
	 */
	private Rectangle glowRegion()
	{
		final Rectangle region = glowBox();
		region.grow(GLOW_HALO_REACH, GLOW_HALO_REACH);
		return region;
	}

	/**
	 * The hero card since AS7: the panel {@code Widgets.column(0)} built before it - the same layout manager with the
	 * same numbers, given the same colour and border by {@link #buildHero} - with one thing added. While the glow runs
	 * it paints the ring round the Refresh link, after its own ground and before its lines, so the ring lies UNDER the
	 * word and can never be drawn over it.
	 *
	 * <p>The card paints it because nothing smaller can. The ring's box runs {@link #GLOW_RING_EXTRA_RIGHT} px past the
	 * link and its halo {@link #GLOW_HALO_REACH} px past that all round, and a component's painting is clipped to its
	 * own bounds - the link's border, where AS4's spark was drawn, cannot reach outside the link. With the ring out
	 * {@link #paintComponent} is {@code JPanel}'s own and nothing else, so every picture of a sidebar with no change
	 * owed - the four pinned renders among them - is byte for byte the picture a plain card makes.
	 */
	private final class HeroCard extends JPanel
	{
		HeroCard()
		{
			super(new DynamicGridLayout(0, 1, 0, 0));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			if (glowRunning())
			{
				paintRing(g, glowBox(), glowLevel());
			}
		}
	}

	/**
	 * Draws the ring round {@code box} at {@code level} (0 to 1), AS7's P1 as it was rendered and chosen: a
	 * {@link #GLOW_STROKE} px ring in {@link #GLOW_COLOUR} lying just inside the box's edge, rounded by
	 * {@link #GLOW_CORNER}, and outside it the halo, two 1 px rings at {@link #GLOW_HALO_NEAR_ALPHA} and
	 * {@link #GLOW_HALO_FAR_ALPHA} of the ring's alpha. Nothing is drawn further inside the box than the ring's own
	 * width, so the word the box surrounds is never painted over, and nothing more than {@link #GLOW_HALO_REACH} px
	 * outside it, which is the region a frame repaints ({@link #glowRegion}). At 0 it draws nothing at all.
	 *
	 * <p>Antialiased with pure stroke control, as rendered - Java2D's default normalisation nudges a stroke onto the
	 * pixel grid and would move a 1.5 px ring off the columns it was chosen on - in a copy of the {@code Graphics}, so
	 * the card's own painting state is untouched. Static and package-private: it depends on nothing but its arguments,
	 * and a test can ask it for any level.
	 */
	static void paintRing(Graphics g, Rectangle box, double level)
	{
		if (level <= 0d)
		{
			return;
		}
		final Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			// Outermost first, as rendered: the halo's two 1 px rings stacked outward from the box's edge (centre lines
			// 1.5 and 0.5 px out), then the ring itself on the inside of the edge (centre line half its width in).
			ring(g2, box, 1.5d, 1f, level * GLOW_HALO_FAR_ALPHA);
			ring(g2, box, 0.5d, 1f, level * GLOW_HALO_NEAR_ALPHA);
			ring(g2, box, -GLOW_STROKE / 2d, GLOW_STROKE, level);
		}
		finally
		{
			g2.dispose();
		}
	}

	/**
	 * One ring of {@link #paintRing}: a {@code width} px stroke whose centre line runs {@code offset} px outside
	 * {@code box}'s edge (inside, when negative), in {@link #GLOW_COLOUR} at {@code alpha}. Its rounding grows with the
	 * offset - {@link #GLOW_CORNER} on the main ring's centre line and 2 px more for every px further out - so the
	 * three rings are concentric. An alpha that rounds to nothing draws nothing.
	 */
	private static void ring(Graphics2D g, Rectangle box, double offset, float width, double alpha)
	{
		final int a = (int) Math.round(255d * Math.max(0d, Math.min(1d, alpha)));
		if (a <= 0)
		{
			return;
		}
		final double arc = GLOW_CORNER + GLOW_STROKE + 2d * offset;
		g.setColor(new Color(GLOW_COLOUR.getRed(), GLOW_COLOUR.getGreen(), GLOW_COLOUR.getBlue(), a));
		g.setStroke(new BasicStroke(width));
		g.draw(new RoundRectangle2D.Double(box.x - offset, box.y - offset, box.width + 2d * offset,
			box.height + 2d * offset, arc, arc));
	}

	/**
	 * Reads a bound off its field. Empty = no bound (0); "100k" / "1.5m" / "1,000" through
	 * {@link MovementMath#parseGp}; anything else marks the field red and leaves the filter alone. The parsed
	 * number is NEVER written back over a spelling that already means it (gotcha 10: parseGp goes through float
	 * above 2^24), so "1.5m" stays "1.5m".
	 *
	 * <p>A text that parsed goes back through {@link #renderBound}, which is what makes a ZERO show as the
	 * field's placeholder (P3): a bound of nothing is spelled "" in this sidebar - it is what a preset, the
	 * fold's "x" and "Clear price range" all leave behind - and a typed "0" that stayed on screen read as a
	 * band of "items priced 0 and up", which is not a band at all. Every road in - Enter, focus lost, a preset,
	 * the dev bridge - therefore ends with the field spelling the filter it produced.
	 */
	private boolean applyBound(Widgets.PlaceholderField field, boolean min)
	{
		final String text = field.getText().trim();
		final long value;
		if (text.isEmpty())
		{
			value = 0L;
		}
		else
		{
			try
			{
				value = MovementMath.parseGp(text);
			}
			catch (ParseException e)
			{
				Widgets.markInvalid(field, true);
				return false;
			}
		}
		changeFilter(min ? filter.withGpMin(value) : filter.withGpMax(value));
		renderBound(field, value);
		return true;
	}

	/** Every user change lands here: repaint, then save and tell the service - unless nothing changed. */
	private void changeFilter(RowFilter next)
	{
		final RowFilter old = filter;
		filter = next == null ? RowFilter.DEFAULT : next;
		renderChips();
		// The hero follows the lit chip at once (M6 step 3): the summary already holds every window.
		renderValue();
		renderControl();
		if (!updating && !filter.equals(old))
		{
			prefs.save(filter);
			// AS: the reader asked for another list, so the list they asked for is built even while the bank is
			// open. Lifted BEFORE the service is told, so an answer that came back at once would still be let through.
			liftBankHold();
			service.setFilter(filter);
		}
	}

	// ---------------------------------------------------------------- painting the header (EDT)

	/** Every header repaint at once - after a build or a publish. */
	private void renderAll()
	{
		renderChips();
		renderValue();
		renderControl();
		renderProblem();
		syncHeader();
	}

	/**
	 * Lights the window chip of the filter's window and the preset whose exact band the filter is; a chip's
	 * tooltip names the window's baseline day when the summary has one (N 3.2).
	 */
	private void renderChips()
	{
		final PortfolioSummary summary = portfolio();
		for (Map.Entry<MovementWindow, JLabel> e : windowChips.entrySet())
		{
			final MovementWindow w = e.getKey();
			final WindowMove move = summary.move(w);
			final LocalDate day = move == null ? null : move.thenDay();
			setHover(e.getValue(), "Guide-price change over the last " + w.label()
				+ (day == null ? "" : " - baseline " + MovementMath.formatDay(day)));
			Widgets.chip(e.getValue(), w == filter.window());
		}
		for (int i = 0; i < presetCells.length; i++)
		{
			Widgets.chip(presetCells[i], presetLit(filter, presetMin(i)));
		}
	}

	/**
	 * Whether a chip whose lower bound is {@code min} is the filter's EXACT band: that bound and no upper one
	 * ("All" is min 0, i.e. no band at all).
	 *
	 * <p>The band and not the chip's PLACE since addendum Z (Z3): the four bounds are the reader's now, so the
	 * only thing that can be asked of a filter is whether it is a given one.
	 */
	static boolean presetLit(RowFilter filter, long min)
	{
		return filter.gpMax() == 0L && filter.gpMin() == min;
	}

	/**
	 * Paints the two fields from the filter - on construction, {@link #applyFilter}, {@link #applyBand} and
	 * after a bound is applied; a chip click never rewrites what the user typed. A field whose text already means
	 * the filter's value keeps its text ("100k" stays "100k" when the config says 100000); otherwise the exact
	 * figure goes in, "" for no bound.
	 */
	private void renderBounds()
	{
		renderBound(minField, filter.gpMin());
		renderBound(maxField, filter.gpMax());
	}

	/**
	 * One field, spelled for {@code value}. A bound of NOTHING has exactly one spelling here - the empty field,
	 * which is what shows the placeholder - so a "0" the user or the bridge typed is rewritten even though it
	 * parses to the value the filter holds (P3); every other value keeps whatever text already means it.
	 */
	private static void renderBound(Widgets.PlaceholderField field, long value)
	{
		final String text = field.getText().trim();
		boolean same;
		if (value == 0L)
		{
			same = text.isEmpty();
		}
		else
		{
			try
			{
				same = MovementMath.parseGp(text) == value;
			}
			catch (ParseException e)
			{
				same = false;
			}
		}
		if (!same)
		{
			field.setText(value == 0L ? "" : MovementMath.formatExact(value));
		}
		Widgets.markInvalid(field, false);
	}

	/**
	 * Paints the hero card from the last status's {@link PortfolioSummary} and the LIT window (M4 as
	 * addendum N draws it): the total, triangle + gp + percent, the footnote "1d vs 08 Sep - bank 09:00", the
	 * edge in the move's colour, then the lines the switches allow (O3, {@link #syncHero}), the tooltip -
	 * listing only the figures that are shown - on the card and every child (playbook 7.5), and the update
	 * line's own hover with its clock (S2). The WHOLE bank,
	 * whatever the gp band says (M1): a Min / Max change repaints nothing here (M6 step 4). Whether the card is
	 * IN the header is {@link #syncHeader}'s business.
	 */
	private void renderValue()
	{
		final PortfolioSummary summary = portfolio();
		final MovementWindow window = filter.window();
		final WindowMove move = summary.move(window);
		// The TRIANGLE keeps the constant and the two figures take the lifted red (see Widgets.MOVE_DOWN_TEXT):
		// a 23 px solid glyph is not small text and needs no lift, while the figures beside it are 18 px.
		final Color moveColour = moveColor(move);
		final Color textColour = moveTextColor(move);
		hero.setBorder(Widgets.card(edgeColor(move)));

		// The total shares its line with the gear (Q1), so what it may take is the card's inner width less the
		// gear and the BorderLayout gap either side of the pair - measured off the gear rather than assumed, the
		// way the band button is measured off the sort button one row down.
		Widgets.setFitted(totalLabel, MovementMath.formatGp(summary.valueNow()),
			CARD_INNER - gearLabel.getPreferredSize().width - 2 * ROW_GAP);
		adoptFittedHover(totalLabel);
		if (move == null)
		{
			triangleLabel.setIcon(null);
			triangleLabel.setBorder(null);
			deltaLabel.setText(MovementMath.DASH);
			deltaLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			// One dash on the line whichever figure is drawn (N 4.6): the percent carries it only while the gp
			// figure is hidden, so the two never print "-  -".
			pctLabel.setText(heroVisibility.gp() ? "" : MovementMath.DASH);
			pctLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		}
		else
		{
			final int sign = Long.signum(move.deltaGp());
			triangleLabel.setIcon(sign > 0 ? Widgets.triangleUp(moveColour) : sign < 0 ? Widgets.triangleDown(moveColour) : null);
			triangleLabel.setBorder(sign == 0 ? null : new EmptyBorder(0, 0, 0, ICON_GAP));
			deltaLabel.setText(MovementMath.formatDelta(move.deltaGp()));
			deltaLabel.setForeground(textColour);
			final boolean hasPct = move.deltaPct() != null;
			pctLabel.setText(hasPct ? MovementMath.formatPct(move.deltaPct(), move.deltaGp()) : MovementMath.DASH);
			pctLabel.setForeground(hasPct ? textColour : ColorScheme.LIGHT_GRAY_COLOR);
		}
		Widgets.setFitted(footnoteLabel, provenanceText(status, window, move, clock.getAsLong(), options), CARD_INNER);
		adoptFittedHover(footnoteLabel);
		// The service knows when it is running blind and says so in one sentence; until now nothing user-facing
		// read either field, so the sidebar was pixel-identical whether the wiki answered five minutes ago or has
		// failed all session, and whether "now" is RuneLite's own price or the wiki's stand-in (checker, B005 /
		// B101). The footnote is the line that carries the provenance, so it is the line that reddens; the whole
		// reason goes under the figure in the card's hover, which has no 191 px budget (AF). NOT the problem row: L11 pins
		// Status.problem() null while a stored baseline stands, and that is deliberate.
		final String warning = status == null || !status.degraded() ? null : status.degradedReason();
		footnoteLabel.setForeground(warning == null ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.PROGRESS_ERROR_COLOR);
		syncHero();

		// AH: the card's hover is one the switch governs, so with it off no target carries a tooltip at all -
		// not a blank one, which Swing would still open as an empty box.
		final String tip = options.showHoverText() ? valueTooltip(summary, heroVisibility, warning) : "";
		for (JComponent c : heroTipTargets)
		{
			setHover(c, tip.isEmpty() ? null : tip);
		}
		// The update line and its own hover (S2, T5), off the same clock the card's tooltip used to print (P2):
		// the line says how often the figures step - which the live switch changes - and the hover says why, and
		// when this client last looked. Both are written here, so the line can never say one thing while its hover
		// explains the other.
		updateLabel.setText(updateText(options));
		setHover(updateLabel, updateTooltip(status == null ? 0L : status.pricesAtMillis(), options));
		hero.revalidate();
		hero.repaint();
	}

	/**
	 * The footnote (N 4.2, 4.6): "1d vs 08 Sep - bank 09:00" with a baseline, "Guide prices - bank 09:00"
	 * without one (between the bank read and the first fetch, or a window with no history - the problem row
	 * says which). While the client is out the clock gives way to "logged out" - "1d vs 08 Sep - logged out" -
	 * because both together do not fit 191 px and the stale-data warning is the one that matters; the card's
	 * tooltip still carries the clock.
	 *
	 * <p><b>An older bank is stamped with its DAY, not with a clock.</b> The snapshot is remembered per account
	 * across sessions ({@code PriceStore.loadBank}) and carries the instant it was CAPTURED, so a bank last
	 * opened on Saturday printed "bank 09:16" on Monday - a stamp that reads exactly like this morning under a
	 * headline that says "Bank value", while every quantity, every holding and the whole-bank total on that
	 * screen are Saturday's and only the prices are current. When the capture is not {@code nowMillis}'s own
	 * calendar day the clock gives way to "bank 08 Sep" (measured: "180d vs 31 Dec - bank 08 Sep" is 164 px of
	 * the card's 191, where the day AND the clock together are 198 and would be cut).
	 *
	 * <p>This arity is the guide-only footnote. Which DAY is stamped once live prices are on is
	 * {@link #footnoteDay}'s answer and the overload below's business (addendum U, line U3); with no live calendar
	 * on the status - every status before addendum U - the two arities print the same line.
	 *
	 * @param nowMillis what "today" is; the panel's {@link #clock}, so this stays a pure function and cannot
	 *                  change its answer for a fixed status at midnight
	 */
	static String provenanceText(@Nullable Status status, @Nullable MovementWindow window, @Nullable WindowMove move,
		long nowMillis)
	{
		return provenanceText(status, window, move, nowMillis, null);
	}

	/**
	 * {@link #provenanceText(Status, MovementWindow, WindowMove, long)} under the view switches (addendum U, line
	 * U3): while live prices are on AND this publish actually priced rows off the traded series, the day the
	 * footnote stamps is the LIVE day for the lit window - the day those rows compared against - and not the guide
	 * baseline day beside it.
	 *
	 * <p>The two are the same day most of the time and different exactly when it matters. The live series counts
	 * back from the live snapshot's own UTC date (U1) while the guide baselines count back from Jagex's anchor day,
	 * so at any hour before Jagex publishes the day's table the guide's "1d" is a day older than the traded one. The
	 * live look of 2026-09-12 found that gap sold as one day: a Partyhat set reading +30 % for "1d" because its
	 * traded bucket was two days back. The footnote is the line that says what "1d" MEANS on this screen, so it
	 * names the day the majority of the figures under it actually used.
	 *
	 * <p>It falls back to the guide day in every other state, which is the line addenda K to S print, word for word:
	 * the switch off, a publish with no live rows at all (nothing on screen used a traded day), a window with no
	 * traded bucket, and a status from before addendum U that carries no live calendar.
	 *
	 * @param options the view switches; null reads as {@link ViewOptions#DEFAULT}. The switch is read as well as the
	 *                count, so throwing live prices off repaints the guide day at once, without waiting for the
	 *                service's own recompute - the same beat the update line changes on
	 */
	static String provenanceText(@Nullable Status status, @Nullable MovementWindow window, @Nullable WindowMove move,
		long nowMillis, @Nullable ViewOptions options)
	{
		final StringBuilder sb = new StringBuilder(48);
		final LocalDate day = footnoteDay(status, window, move, options);
		if (day != null)
		{
			sb.append((window == null ? MovementWindow.DEFAULT : window).label()).append(" vs ")
				.append(MovementMath.formatDay(day));
		}
		else
		{
			sb.append("Guide prices");
		}
		if (status != null && !status.loggedIn())
		{
			sb.append(" - logged out");
		}
		else
		{
			sb.append(" - bank ").append(bankStamp(status == null ? 0L : status.bankAtMillis(), nowMillis));
		}
		return sb.toString();
	}

	/**
	 * Which day the footnote stamps for the lit window (addendum U, line U3): the LIVE day while live prices are on
	 * and this publish put at least one row on the traded series, and the guide baseline day otherwise. Null - the
	 * footnote then reads "Guide prices" - only when there is no move at all, exactly as before addendum U.
	 *
	 * <p>Both halves of the live test are load-bearing. The SWITCH, because the panel repaints the card the moment
	 * it is thrown and the footnote must not go on naming a traded day the sidebar is no longer showing. The COUNT
	 * ({@code liveRows}), because with the switch on and nothing liquid enough to qualify every figure on screen is
	 * a guide figure, and stamping the traded calendar over a screen of guide rows would be the same lie addendum U
	 * removed, told the other way round.
	 *
	 * @param options the view switches; null reads as {@link ViewOptions#DEFAULT}
	 */
	@Nullable
	static LocalDate footnoteDay(@Nullable Status status, @Nullable MovementWindow window, @Nullable WindowMove move,
		@Nullable ViewOptions options)
	{
		final LocalDate guideDay = move == null ? null : move.thenDay();
		if (guideDay == null || !(options == null ? ViewOptions.DEFAULT : options).livePrices())
		{
			return guideDay;
		}
		final Status.LiveStatus live = status == null ? null : status.live();
		if (live == null || live.liveRows() <= 0)
		{
			return guideDay;
		}
		final LocalDate liveDay = live.windowDays().get(window == null ? MovementWindow.DEFAULT : window);
		return liveDay == null ? guideDay : liveDay;
	}

	/**
	 * How a bank snapshot's age is stamped: {@link MovementMath#DASH} without one, the clock while it was
	 * captured on {@code nowMillis}'s own local day, and the calendar day otherwise. Both instants are read in
	 * the viewer's zone, the zone {@link MovementMath#formatTime} already prints in - this is a wall-clock
	 * capture, not the UTC guide-table marker {@link MovementMath#formatDay} was written for, and only the shape
	 * of the day is borrowed.
	 */
	static String bankStamp(long capturedAtMillis, long nowMillis)
	{
		if (capturedAtMillis <= 0L)
		{
			return MovementMath.DASH;
		}
		final ZoneId zone = ZoneId.systemDefault();
		final LocalDate captured = Instant.ofEpochMilli(capturedAtMillis).atZone(zone).toLocalDate();
		if (nowMillis > 0L && !captured.equals(Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()))
		{
			return MovementMath.formatDay(captured);
		}
		return MovementMath.formatTime(capturedAtMillis);
	}

	/**
	 * Paints the control row (N 3.3, addendum W lines W2 and W3): the sort button's COLUMN and its direction
	 * arrow, then the band button - stating its band in words (N 4.4 control 3) - fitted to what the sort button
	 * leaves.
	 *
	 * <p>The arrow is set here and only here, because it is a piece of the filter and not a decoration of the
	 * widget: {@code Widgets.triangle(descending)} points down for biggest first and up for smallest, and the
	 * button's preferred width - which is what the band button is fitted against on the next line - is the label
	 * plus that one glyph either way.
	 *
	 * <p>The sort button's tooltip is the constant {@link #SORT_BUTTON_TIP} (addendum X, line X2). It is set
	 * here, beside the text and the arrow, rather than once in {@link #buildControlRow}, so that one method
	 * owns everything the sort button says; it depends on nothing, which is the whole of X2 - the column and
	 * the direction are on the button's face and what a press will do is on the menu entry (X1).
	 *
	 * <p>The band button's tooltip carries the COUNT: addendum N deleted the count line deliberately
	 * (section 3 §5), so after setting a band there is otherwise nowhere on the panel that says whether it
	 * matched 3 items or 300 - the "Show n more" row is absent whenever the band matched less than a page.
	 */
	private void renderControl()
	{
		sortButton.setText(filter.sort().label());
		sortButton.setIcon(Widgets.triangle(filter.descending()));
		setHover(sortButton, SORT_BUTTON_TIP);
		final boolean band = bandOn();
		final int room = W - 2 * ROW_GAP - sortButton.getPreferredSize().width - ROW_GAP;
		Widgets.setFitted(bandTarget, bandLabel(filter.gpMin(), filter.gpMax()), room - TRIANGLE_ICON - ICON_GAP);
		adoptFittedHover(bandTarget);
		setHover(bandTarget, (band ? "Showing items " + bandSentence(filter.gpMin(), filter.gpMax()) : BAND_TIP)
			+ " - " + countSentence());
	}

	/**
	 * "12 of 529 items" for the band button's tooltip; the listed rows against the bank's tradeable stacks.
	 *
	 * <p>With a stack the guide table cannot price it also names them - "529 items, 3 with no guide price"
	 * (checker, B105). {@code MovementMath.apply} drops an unpriced row before the band is even considered, and
	 * addendum N deleted the count line, so a newly released item simply vanished from the list with nothing on
	 * the panel accounting for it. The count comes from the whole-bank sums, which are computed over the bank
	 * BEFORE the filter, so it is the same number with a band set or without one.
	 */
	private String countSentence()
	{
		final int total = status == null ? 0 : status.bankItems();
		final PortfolioSummary summary = portfolio();
		final int unpriced = Math.max(0, summary.itemsTotal() - summary.itemsPriced());
		return rows.size() + (total > rows.size() ? " of " + total : "") + (rows.size() == 1 ? " item" : " items")
			+ (unpriced > 0 ? ", " + unpriced + " with no guide price" : "");
	}

	/**
	 * Paints the problem row (N 3.6): the status's problem sentence, fitted, with the whole sentence as its
	 * tooltip; red by the UNCHANGED {@link #isErrorStatus} rule, grey for L11's "No 180d history".
	 */
	private void renderProblem()
	{
		Widgets.setFitted(problemLabel, problemText(), W - 2 * ROW_GAP);
		adoptFittedHover(problemLabel);
		problemLabel.setForeground(isErrorStatus() ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
	}

	/**
	 * Puts the right rows into the header column, in order, and nothing else: the hero card (the chips are
	 * inside it), the control row, the fold while open and the problem row while there is a problem - all of
	 * them only while a bank is loaded (N section 3 §3: LOGIN and NO_BANK empty the header). Rows are added
	 * and removed, never hidden (playbook 7.5), and nothing is touched when the set is already right, so a
	 * status-only publish causes no flicker (C30).
	 */
	private void syncHeader()
	{
		final List<Component> want = new ArrayList<>(4);
		if (bankLoaded())
		{
			want.add(hero);
			want.add(controlRow);
			if (foldOpen)
			{
				want.add(fold);
			}
			if (!problemText().isEmpty())
			{
				want.add(problemLabel);
			}
		}
		// The flag covers the one case the diff above cannot: the fold genuinely leaving (the bank went away, or
		// the user closed it) while a gp field has the caret. A focus lost to THAT must not apply a half-typed
		// bound. It is inert whenever the toolkit delivers the focus event later from the queue - which is why
		// the diff, and not this, is what makes the ordinary publish safe.
		syncingHeader = true;
		try
		{
			replaceChildren(header, want);
		}
		finally
		{
			syncingHeader = false;
		}
	}

	/**
	 * Opens or closes the price fold (N 3.5): the band button's click, and the bridge's {@code fold=toggle}
	 * (addendum AA, line AA2). It presses {@link #pressFold}, so the state it leaves the fold in is REMEMBERED -
	 * a fold the reader shut that stood open again on the next launch would be a control that argues back.
	 */
	public void toggleFold()
	{
		pressFold(!foldOpen);
	}

	/**
	 * The band button's press, said as the state it aims at - the bridge's {@code fold=on|off} takes this same
	 * road (AA2): opens or closes the fold AND writes the choice through {@link Prefs#saveFoldOpen}, so the stored
	 * config follows. It is the round trip {@link #setOptions} and {@link #setPresets} make for the gear menu's
	 * switches, and nothing is written when nothing changed.
	 *
	 * <p>A stopped panel writes nothing either: {@link #setFoldOpen} would refuse the change, and a write without
	 * one is a stored choice nobody made (contract C33).
	 */
	public void pressFold(boolean open)
	{
		if (stopped)
		{
			return;
		}
		final boolean changed = open != foldOpen;
		setFoldOpen(open);
		if (changed && !updating)
		{
			prefs.saveFoldOpen(open);
		}
	}

	/**
	 * The fold changed under us - the plugin's {@code ConfigChanged} for {@code foldOpen}, i.e. the settings page
	 * (AA1) - so the header takes or loses the fold's two rows and nothing else happens: no write back (the config
	 * already knows) and no service call, because which rows are LISTED does not depend on whether the controls
	 * that set the band are on screen. The same state again is a no-op diff inside {@link #syncHeader}.
	 *
	 * <p>It is also how {@link #applyMin} and {@link #applyMax} open the fold so a shot shows what they did: that
	 * is the bridge pointing at a control, not the reader choosing to keep it open, so it writes nothing either.
	 *
	 * <p>The guard is {@link #applyFilter}'s: both config roads post their repaint with {@code invokeLater}, so
	 * one can be drained after {@code shutDown} has already dropped this panel (contract C33).
	 */
	public void setFoldOpen(boolean open)
	{
		if (stopped)
		{
			return;
		}
		foldOpen = open;
		syncHeader();
	}

	// ---------------------------------------------------------------- the column menu (W3)

	/**
	 * The sort menu, built but not shown (addendum W, line W3): four {@link JMenuItem}s, one per
	 * {@link SortMode}, in the enum's own order, the lit column carrying the button's own arrow as its icon and
	 * the other three no icon at all. Plain {@code JMenuItem}s, not radio items: no dependence on the
	 * look-and-feel's radio mark.
	 *
	 * <p><b>The icon is the same glyph as the button's</b>, which is the whole of W's fix. The menu says which
	 * column is lit (the orange) and which way it runs (the arrow) in one row, so the reader can see what
	 * picking it again will do before they do it. No entry carries a grey qualifier any more - addenda N and V
	 * hung "per item" / "unit price" / "stack value" here, and W3 deletes them: four column names need no gloss,
	 * and what a row reads as is the ROW's own business (addendum X, line X2; since addendum AN it prints the
	 * stack on one line and one item on the next, so no switch qualifies a column any more).
	 *
	 * <p>No separators either: three groups of two became four columns, and a rule between single entries is a
	 * line drawn round nothing.
	 *
	 * <p>Every entry is a PRESS through {@link #clickSort}, exactly as the bridge's {@code sort=} is - the lit
	 * one flips, another lights biggest first - so there is one rule for what a column press does and one place
	 * it lives. The menu is rebuilt on every open, so nothing here needs a refresh of its own.
	 *
	 * <p><b>Each entry's hover says what picking it will do</b> (addendum X, line X1;
	 * {@code docs/bank-price-movement-addendum-X-2026-09-13.md}): the LIT column's entry reads
	 * "Toggle &lt;label&gt;" and every other "Sort by &lt;label&gt;", the label verbatim and the entry TEXT
	 * untouched. That is the one thing the menu could not show - the orange says which column is lit and the
	 * arrow which way it runs, but neither says that the lit one turns over rather than doing nothing when it
	 * is picked again. The two texts are chosen where the lit column is read, so the rebuild on each open is
	 * all that keeps them true, and the button's own hover is free to be the one phrase of X2.
	 */
	JPopupMenu buildSortMenu()
	{
		final JPopupMenu menu = new JPopupMenu();
		menu.setBorder(new EmptyBorder(5, 5, 5, 5));
		final SortMode current = filter.sort();
		for (SortMode mode : SortMode.values())
		{
			final boolean on = mode == current;
			final JMenuItem item = new JMenuItem(mode.label(), on ? Widgets.triangle(filter.descending()) : null);
			item.setFont(Widgets.sans(12));
			item.setForeground(on ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR);
			setHover(item, (on ? "Toggle " : "Sort by ") + mode.label());
			item.addActionListener(e -> clickSort(mode));
			menu.add(item);
		}
		return menu;
	}

	/**
	 * Shows the sort menu under the sort button - {@code menu.show(anchor, x, y)} behind an
	 * {@code isShowing()} guard, one at a time ({@code BeamPickerPopup.java:109-160}; RuneLite disables
	 * lightweight popups, and a menu can only be placed against something on the screen).
	 */
	private void openSortMenu()
	{
		closeSortMenu();
		if (stopped || !sortButton.isShowing())
		{
			return;
		}
		final JPopupMenu menu = buildSortMenu();
		sortMenu = menu;
		menu.show(sortButton, 0, sortButton.getHeight());
	}

	/** Takes down whichever of the panel's two menus is standing - the sidebar moving away closes both (Q1). */
	private void closeMenus()
	{
		closeSortMenu();
		closeGearMenu();
	}

	/**
	 * Takes the gear menu down: the sidebar moving away (Q1), {@link #stop()}, and the OK button (AB2).
	 *
	 * <p>The hide is what commits the preset boxes and stamps {@link #menuClosedAtMillis}, because Swing fires
	 * {@code popupMenuWillBecomeInvisible} for it - one road, however the close was asked for.
	 */
	private void closeGearMenu()
	{
		if (heroMenu != null && heroMenu.isVisible())
		{
			heroMenu.setVisible(false);
		}
	}

	private void closeSortMenu()
	{
		final JPopupMenu open = sortMenu;
		sortMenu = null;
		if (open != null)
		{
			open.setVisible(false);
		}
	}

	// ---------------------------------------------------------------- the rows (EDT)

	/**
	 * What the rows ON SCREEN were built from, as one immutable value: the window and the baseline day their
	 * tooltips carry (L7) and the whole filter they were listed under. A publish is measured against it twice,
	 * and the two questions are deliberately different ones.
	 *
	 * <p>{@link #redrawnBy} is "would these rows be drawn differently?" - the window and the baseline day are
	 * printed in every row's tooltip, and the ordering decides which row is which; a new gp band moves rows in or
	 * out, which the row LIST itself already shows, so it is not asked here.
	 *
	 * <p>{@link #asksDifferentList} is "did the reader ask for another list?" (B107) - any choice of theirs, band
	 * included. That is the one that sends them back to the top; everything else - a deposit, a withdrawal, new
	 * prices, a new baseline day - is the same list restated, and leaves them where they were.
	 */
	private static final class ListContext
	{
		private final MovementWindow window;
		@Nullable
		private final LocalDate thenDay;
		private final RowFilter filter;

		ListContext(MovementWindow window, @Nullable LocalDate thenDay, RowFilter filter)
		{
			this.window = window == null ? MovementWindow.DEFAULT : window;
			this.thenDay = thenDay;
			this.filter = filter == null ? RowFilter.DEFAULT : filter;
		}

		/**
		 * Whether rows built for {@code next} would read differently from these, even where the row list itself
		 * is unchanged: another window or baseline day (both are stamped into every row's tooltip, L7) or another
		 * sort column.
		 */
		boolean redrawnBy(ListContext next)
		{
			return window != next.window || !Objects.equals(thenDay, next.thenDay)
				|| filter.sort() != next.filter.sort();
		}

		/** Whether {@code next} is a list the READER asked for rather than this one restated (B107). */
		boolean asksDifferentList(ListContext next)
		{
			return window != next.window || !filter.equals(next.filter);
		}
	}

	/**
	 * {@link PriceService.Listener}: the EDT, by contract; re-posted if it is not.
	 *
	 * <p>While the sidebar is showing another panel this only REMEMBERS the publish (design D8: nothing while
	 * nobody is looking). {@code setVisible(false)} stops the service fetching, but the bank keeps changing -
	 * every deposit, withdrawal and rearrange publishes - and each publish whose quantities differ rebuilt a
	 * whole page: ~2,700 Swing components on the EDT and up to {@link #ROWS_PER_PAGE} sprite renders queued onto
	 * the game thread, for a panel nobody can see. The last publish is replayed whole by {@link #onActivate}, so
	 * what the reader gets when they open the sidebar is the same picture they would have got had it been open.
	 *
	 * <p><b>And while the bank is open</b> (addendum AS, section 7.2): the same store, for the same reason - a player
	 * gearing up at the bank is the moment a page rebuild costs most - replayed when the bank closes. Two things lift
	 * it: the reader's own act ({@link #liftBankHold}), and a publish carrying a bank the sidebar has not drawn
	 * ({@link #carriesNewBank}) - a read, where the hold is for re-statements.
	 */
	private void onRows(@Nullable List<MovementRow> newRows, @Nullable Status newStatus)
	{
		if (stopped)
		{
			return;
		}
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> onRows(newRows, newStatus));
			return;
		}
		if (carriesNewBank(newStatus))
		{
			// A read, not a re-statement: the hold is lifted for it (AS; see carriesNewBank). A no-op with the bank
			// closed, and still stored while the sidebar is hidden - to be built on the next show.
			liftBankHold();
		}
		if (holding())
		{
			pendingPublish = true;
			pendingRows = newRows;
			pendingStatus = newStatus;
			return;
		}
		// Anything still stored is OLDER than this publish - they arrive in order, and each is the service's whole
		// state - so it is dropped here. Kept, the bank closing would replay it over the newer picture.
		pendingPublish = false;
		pendingRows = null;
		pendingStatus = null;
		final List<MovementRow> safe = newRows == null ? Collections.emptyList() : newRows;
		final MovementWindow window = newStatus != null && newStatus.window() != null ? newStatus.window() : filter.window();
		final ListContext next = new ListContext(window, newStatus == null ? null : newStatus.thenDay(), filter);
		final boolean rebuild = list.redrawnBy(next) || !safe.equals(rows);
		// A DIFFERENT list is one the reader asked for: another window (which the status carries, and which can
		// land a publish after the filter already changed) or any other choice of theirs. Everything else - a
		// deposit, a withdrawal, new prices, a new baseline day - is the same list restated (B107).
		final boolean newList = list.asksDifferentList(next);
		status = newStatus;
		if (rebuild)
		{
			rows = safe;
			list = next;
			rebuildRows(newList);
		}
		renderChips();
		renderValue();
		renderControl();
		renderProblem();
		syncHeader();
		showCard(chooseCard());
	}

	/**
	 * Rebuilds the list from {@link #rows}, and decides where the reader ends up.
	 *
	 * <p><b>Why that is a decision at all.</b> This runs on every publish whose rows differ, and the bank
	 * publishes on every deposit, withdrawal and rearrange ({@code ItemContainerChanged}) - so a reader who
	 * opened three pages and scrolled two-thirds down to read the 90 d movers used to be thrown back to row 1
	 * of 250, with both extra pages gone, by each single item they withdrew (B107). The pages they opened and
	 * the place they scrolled to are their work; a bank that changed under them is not a reason to undo it.
	 *
	 * <p>The top IS the new answer for a list the reader ASKED for - another window, another ordering or
	 * direction, another gp band - because the row they were looking at is not the row that belongs there any
	 * more. That, and only that, is {@code newList}.
	 *
	 * <p><b>Why the restore is posted.</b> The whole rebuild happens in one go on the EDT, so nothing lays the
	 * column out while it is empty and the reader's position survives it; what can still move it is the LAYOUT
	 * that follows, where a view of a different height makes the viewport pull a position that now falls past
	 * the end back up, and the scrollbar's value follows it. A value written here would be undone by that
	 * layout, so the write is posted behind it - and {@code BoundedRangeModel.setValue} clamps it to
	 * {@code maximum - visibleAmount}, so a list that really did get shorter leaves the reader at its bottom
	 * rather than past its end.
	 *
	 * @param newList whether these rows are a different list, not the same one restated
	 */
	private void rebuildRows(boolean newList)
	{
		final JScrollBar bar = scroll.getVerticalScrollBar();
		// Pages, not rows: shown is only ever off a page boundary when it reached the end of the list, and a page
		// count is what "Show n more" sold the reader.
		final int keepPages = newList ? 1 : Math.max(1, (shown + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
		final int keepScroll = newList ? 0 : bar.getValue();
		rowsColumn.removeAll();
		shown = 0;
		for (int page = 0; page < keepPages && shown < rows.size(); page++)
		{
			addPage();
		}
		pruneImages();
		updateShowMore();
		rowsColumn.revalidate();
		rowsColumn.repaint();
		if (keepScroll <= 0)
		{
			bar.setValue(0);
		}
		else
		{
			SwingUtilities.invokeLater(() -> restoreScroll(keepScroll));
		}
		rebuilds++;
	}

	/** Puts the reader back where they were, clamped by the model to the bottom of the new list (B107). */
	private void restoreScroll(int value)
	{
		if (stopped)
		{
			return;
		}
		scroll.getVerticalScrollBar().setValue(value);
	}

	/**
	 * Which item ids are showing the long row hover (addendum AG), remembered HERE rather than in a row.
	 *
	 * <p>A {@link MovementRowPanel} lives only until the next publish - a refresh, a bank opening, the
	 * half-hourly recheck - because {@code addPage} builds the list from new instances every time. A row that
	 * held its own state would therefore fold itself up while the reader was still looking at it. Keyed by item
	 * id, so it survives a re-sort and a change of price band too, and never cleared: a reader who opened three
	 * rows means it, and the set costs one Integer each.
	 */
	private final Set<Integer> expandedIds = new HashSet<>();

	/** The panel's one {@link MovementRowPanel.Expansion}, handed to every row it builds. */
	private final MovementRowPanel.Expansion expandedRows = new MovementRowPanel.Expansion()
	{
		@Override
		public boolean isExpanded(int itemId)
		{
			return expandedIds.contains(itemId);
		}

		@Override
		public void setExpanded(int itemId, boolean expanded)
		{
			if (expanded)
			{
				expandedIds.add(itemId);
			}
			else
			{
				expandedIds.remove(Integer.valueOf(itemId));
			}
		}
	};

	/** Builds the next page of rows (contract C32: never every row at once). */
	private void addPage()
	{
		final int end = Math.min(rows.size(), shown + ROWS_PER_PAGE);
		for (int i = shown; i < end; i++)
		{
			final MovementRow row = rows.get(i);
			rowsColumn.add(new MovementRowPanel(row, image(row), list.window, list.thenDay, options, expandedRows));
		}
		shown = end;
	}

	/**
	 * The sprite for a row, on the EDT (contract C31); null - and a blank cell - if the manager cannot.
	 *
	 * <p><b>Why this keeps its own map.</b> {@code ItemManager}'s sprite cache holds 128 images
	 * ({@code ItemManager.java:221-222}), so one page of {@link #ROWS_PER_PAGE} rows cannot fit in it: walking
	 * 250 keys evicts the earliest, the next rebuild misses on all of them, and every miss queues a
	 * {@code client.createItemSprite} onto the GAME thread ({@code ItemManager.java:489-508}). Since a rebuild
	 * happens on every bank change, banking with the sidebar open would push ~250 sprite renders per deposit at
	 * the client. A held {@link AsyncBufferedImage} keeps its pixels after the manager evicts it, so remembering
	 * the ones this panel asked for means a rebuild that changed only prices re-renders nothing and no icon
	 * blinks blank.
	 *
	 * <p>The key is the manager's own - id, quantity AND stackable - because a stackable sprite carries its stack
	 * number in the picture; keying on the id alone would paint yesterday's count after a deposit. A null is
	 * never remembered, so a picture the manager could not give yet can still arrive later.
	 */
	@Nullable
	private AsyncBufferedImage image(MovementRow row)
	{
		final long key = imageKey(row);
		final AsyncBufferedImage cached = images.get(key);
		if (cached != null)
		{
			return cached;
		}
		try
		{
			final AsyncBufferedImage image = itemManager.getImage(row.id(), row.quantity(), row.stackable());
			if (image != null)
			{
				images.put(key, image);
			}
			return image;
		}
		catch (RuntimeException e)
		{
			log.debug("no image for item {}", row.id(), e);
			return null;
		}
	}

	/** {@code ItemManager}'s own image key - id, quantity, stackable - packed into one long. */
	private static long imageKey(MovementRow row)
	{
		return ((long) row.id() << 33) | ((long) Math.max(0, row.quantity()) << 1) | (row.stackable() ? 1L : 0L);
	}

	/**
	 * Drops the sprites of rows that are no longer listed, so a long session over many banks does not hold every
	 * picture it ever drew (36 x 32 ARGB is ~4.6 KB each). Called at the end of a rebuild, where the row list is
	 * the new truth.
	 */
	private void pruneImages()
	{
		if (images.isEmpty())
		{
			return;
		}
		// One set, reused: a rebuild happens on every bank change and a bank is up to a few thousand rows, so
		// this used to build and throw away a set that size per deposit. Cleared at both ends, so it holds no key
		// between rebuilds - only its (grown) table, which is the point.
		liveKeys.clear();
		for (MovementRow row : rows)
		{
			liveKeys.add(imageKey(row));
		}
		images.keySet().retainAll(liveKeys);
		liveKeys.clear();
	}

	/** The next page (contract C32); the bridge's {@code more}. Nothing to add is not an error. */
	public void showMore()
	{
		if (stopped || shown >= rows.size())
		{
			return;
		}
		addPage();
		updateShowMore();
		rowsColumn.revalidate();
		rowsColumn.repaint();
	}

	/**
	 * Adds or removes the "Show &lt;remainder&gt; more" row rather than hiding it - a hidden row still takes
	 * its place in a column (N section 3 §5: no count line, no "Show all").
	 *
	 * <p>The tooltip is written HERE, beside the text: set once in the constructor from {@link #ROWS_PER_PAGE}
	 * it said "Build the next 250 rows" under a label reading "Show 12 more" - which is every bank's last click.
	 */
	private void updateShowMore()
	{
		listColumn.remove(showMoreRow);
		if (shown < rows.size())
		{
			final int remainder = rows.size() - shown;
			showMoreLabel.setText(showMoreText(remainder));
			setHover(showMoreLabel, "Build the next " + Math.min(remainder, ROWS_PER_PAGE) + " rows");
			listColumn.add(showMoreRow);
		}
		listColumn.revalidate();
		listColumn.repaint();
	}

	/** "Show 279 more" (N section 3 §5). */
	public static String showMoreText(int remainder)
	{
		return "Show " + remainder + " more";
	}

	/** Rows built so far (the first {@code n} of {@link #totalRows()}). */
	public int shownRows()
	{
		return shown;
	}

	/** Rows the service published for the current filter. */
	public int totalRows()
	{
		return rows.size();
	}

	/**
	 * Whether the problem row is painted red. Red means "something failed, and it may be yours to act on": the
	 * cooldown "Refreshed 12 s ago - wait" and "Wiki history down - no movement" both name something the
	 * reader can do. TWO sentences are exempt, and neither is a failure.
	 *
	 * <p>Addendum L's "No 180d history" (L11): a window whose target date predates the whole revision index is a
	 * fact about the wiki page - nothing was requested, nothing failed and no retry will change it - so it reads
	 * in the ordinary grey and the acceptance list's "no red status" for it holds.
	 *
	 * <p>And K7's "No 1d history yet", which addendum N 3.6 had red: it is published only while the FIRST index
	 * or table fetch is in flight, so on a fresh profile it is the first thing a new user ever sees under their
	 * new bank value - in the same red as a real failure, for a state that clears itself in seconds. It is
	 * progress, not a fault, and it names nothing to do; grey is what it means.
	 *
	 * <p>The status says which kind it published ({@link PriceService.ProblemKind#severe()}), decided where the
	 * sentence was written. This used to rebuild those two sentences and compare strings, so a reworded one
	 * silently turned red.
	 */
	private boolean isErrorStatus()
	{
		return status != null && status.problemKind().severe();
	}

	/** The problem row's sentence: the status's problem, or "" when there is none. */
	String problemText()
	{
		final String problem = status == null ? null : status.problem();
		return problem == null ? "" : problem;
	}

	/**
	 * The whole status sentence, for the bridge's {@code status} field (N 3.6): {@code Status.text()} plus the
	 * problem sentence when there is one and the text does not already carry it (contract C29, K7). On the
	 * screen the sentence is split between the card's tooltip ({@code headerText()}) and the problem row.
	 */
	String statusText()
	{
		if (status == null)
		{
			return "";
		}
		final String text = status.text() == null ? "" : status.text();
		final String problem = status.problem();
		if (problem == null || problem.isEmpty() || text.contains(problem))
		{
			return text;
		}
		return text.isEmpty() ? problem : text + " - " + problem;
	}

	// ---------------------------------------------------------------- pure helpers (for the tests and the bridge)

	/** The last status's summary; {@link PortfolioSummary#EMPTY} before one, or for a status that carries none. */
	private PortfolioSummary portfolio()
	{
		final PortfolioSummary summary = status == null ? null : status.portfolio();
		return summary == null ? PortfolioSummary.EMPTY : summary;
	}

	private boolean bankLoaded()
	{
		return status != null && status.bankLoaded();
	}

	private boolean bandOn()
	{
		return filter.gpMin() != 0L || filter.gpMax() != 0L;
	}

	/** The band button (N 4.4 control 3): "All items", "100k+", "up to 5m", "100k - 5m". */
	static String bandLabel(long min, long max)
	{
		return bandParts(min, max, "All items", "%s+", "up to %s", "%s - %s");
	}

	/** The band in words for a tooltip - "from 1m", "from 100k to 5m", "up to 5m" - or "" without one. */
	static String bandSentence(long min, long max)
	{
		return bandParts(min, max, "", "from %s", "up to %s", "from %s to %s");
	}

	/** The EMPTY card's description of the band (N section 3 §6): "Your band is 1m and up" / "from 100k to 5m" / "up to 5m". */
	static String bandDescription(long min, long max)
	{
		return "Your band is " + bandParts(min, max, "", "%s and up", "up to %s", "from %s to %s");
	}

	/**
	 * The one shape all three band sentences have: a band is NONE, a lower bound alone, an upper bound alone or
	 * both, and each of the three only differs in the words it puts round the {@link MovementMath#formatGp}
	 * figures. Written once so a fourth shape - or a bound that starts counting 0 as a bound - cannot be added to
	 * two of the three.
	 *
	 * @param none    the whole answer when neither bound is set
	 * @param minOnly a pattern taking the lower bound
	 * @param maxOnly a pattern taking the upper bound
	 * @param both    a pattern taking the lower bound then the upper one
	 */
	private static String bandParts(long min, long max, String none, String minOnly, String maxOnly, String both)
	{
		final boolean hasMin = min > 0L;
		final boolean hasMax = max > 0L;
		if (!hasMin && !hasMax)
		{
			return none;
		}
		if (!hasMax)
		{
			return String.format(minOnly, MovementMath.formatGp(min));
		}
		if (!hasMin)
		{
			return String.format(maxOnly, MovementMath.formatGp(max));
		}
		return String.format(both, MovementMath.formatGp(min), MovementMath.formatGp(max));
	}

	/**
	 * The bridge's old line 2 - "1d   +12.4m   +1.0%", "1d   -   -" without a baseline (M4) - SYNTHESISED for
	 * {@code describe()} so addendum M's live step 3 keeps reading it (N section 3 §8), whatever the switches
	 * hide on the card (O3: the bridge still echoes the portfolio in full). The percentage is truncated toward
	 * zero and signed by the gp figure, like a row's (L2).
	 */
	static String moveText(@Nullable MovementWindow window, @Nullable WindowMove move)
	{
		final String label = (window == null ? MovementWindow.DEFAULT : window).label();
		if (move == null)
		{
			return label + VALUE_SEP + MovementMath.DASH + VALUE_SEP + MovementMath.DASH;
		}
		return label + VALUE_SEP + MovementMath.formatDelta(move.deltaGp()) + VALUE_SEP
			+ MovementMath.formatPct(move.deltaPct(), move.deltaGp());
	}

	/** Green for a rise, red for a fall, the dim grey for zero or for no baseline (M4) - the row rule, on the sum. */
	static Color moveColor(@Nullable WindowMove move)
	{
		return Widgets.move(signum(move), Widgets.Kind.MARK);
	}

	/**
	 * {@link #moveColor} as the card's two FIGURES are painted: the same green and grey, and
	 * {@link Widgets#MOVE_DOWN_TEXT} - the lifted red, 5.05:1 where the constant is 3.63:1 - for a fall. The
	 * triangle and the card's edge keep {@link #moveColor} / {@link #edgeColor}, so the direction marks and the
	 * numbers still agree; this is the row's {@link MovementRowPanel#textChangeColor} rule on the sum.
	 */
	static Color moveTextColor(@Nullable WindowMove move)
	{
		return Widgets.move(signum(move), Widgets.Kind.FIGURE);
	}

	/**
	 * The hero card's edge (N 3.1, O3 - it stays whatever the switches say: a direction hint, not a figure):
	 * the move colour's {@code darker()} for a mover, the card's own grey - an invisible edge that still keeps
	 * the card's width - for zero or no move.
	 */
	static Color edgeColor(@Nullable WindowMove move)
	{
		return Widgets.move(signum(move), Widgets.Kind.EDGE);
	}

	/** Which way the whole bank went over a window, for {@link Widgets#move}; no move at all reads as flat. */
	private static int signum(@Nullable WindowMove move)
	{
		return move == null ? 0 : Long.signum(move.deltaGp());
	}

	/**
	 * The hero card's hover: the bank value as an exact gp figure with thousands separators, and nothing else
	 * (addendum AF).
	 *
	 * <pre>
	 * 446,901,681 gp
	 * </pre>
	 *
	 * <p>The card itself PRINTS a rounded figure - "446.9m" - because the caption row has 191 px to spend, so the
	 * one thing the rounding takes away is the gp to the unit, and that is the one thing this hover now gives
	 * back. Everything it used to carry (the stack counts, a line per window, the rule the sums follow, the
	 * status sentence) is either already drawn on the card or one click away on the controls directly beneath it,
	 * and eight lines of it stood over the sidebar whenever the pointer crossed the number.
	 *
	 * <p>"" when the value is not drawn at all (the gear's show/hide switch, O2), because a hover explains a
	 * number and there is then no number under the pointer - the caller sets no tooltip at all.
	 *
	 * <p><b>A degraded fetch still says so</b>, on a second line under the figure. It is the one thing here that
	 * the card cannot repeat: the footnote reddens but is fitted to the card's width and cannot carry the whole
	 * sentence (checker, B005 / B101), and it appears only when something is actually wrong.
	 * {@code warning} is {@link Status#degradedReason()}, which {@code PriceService} publishes when the last
	 * history fetch failed and the baselines on screen are whatever was stored (L11), or when too few bank stacks
	 * compare for the anchor day to be derived and the newest wiki table stands in as "now" (L3).
	 *
	 * <p><b>What was removed, and how to bring it back.</b> The detail builder and the five clause helpers it
	 * alone used - {@code windowHead}, {@code sumsRule}, {@code coinsClause}, {@code liveClause} and
	 * {@code carriedClause} - were deleted whole in the addendum AF commit rather than left unreachable, so the
	 * revert is one {@code git show} away and no dead code ships meanwhile.
	 *
	 * @param shown   which figures are drawn; null reads as {@link HeroVisibility#ALL}
	 * @param warning the degraded sentence, or null when the service is confident
	 */
	static String valueTooltip(PortfolioSummary summary, @Nullable HeroVisibility shown, @Nullable String warning)
	{
		final HeroVisibility v = shown == null ? HeroVisibility.ALL : shown;
		if (!v.value())
		{
			return "";
		}

		// The unit rides with the figure: the card's own total is drawn under the caption "Bank value", which
		// says what it is, but a hover opens over the sidebar on its own and a bare nine-digit number there
		// names no unit at all.
		final String exact = MovementMath.formatExact(summary.valueNow()) + GP_SUFFIX;
		if (warning == null || warning.isEmpty())
		{
			// One line, so no HTML: a plain string is the tooltip Swing draws fastest and the one a test can
			// compare without unescaping.
			return exact;
		}
		return "<html>" + exact + "<br>" + Widgets.escapeHtml(warning) + "</html>";
	}

	/**
	 * The update line's own hover (addendum S, line S2): why the figures sit still, and when this client last
	 * looked.
	 *
	 * <pre>
	 * 2h Bank Portfolio Tracker uses the Grand Exchange guide price, which Jagex publishes once a day at a varying hour.
	 * Refresh re-checks for it, and the plugin re-checks by itself every 30 minutes.
	 * Last checked 09:05.
	 * </pre>
	 *
	 * <p>Three sentences, one per line, in the card's own tooltip HTML: Swing does not wrap a tooltip, so the one
	 * 200-character line they make unbroken would open a box several times the width of the client's sidebar, and
	 * the card beside it already reads as a stack of lines. The clock is {@code Status.pricesAtMillis()} through
	 * {@link MovementMath#formatTime}, the same path the card's tooltip printed it by until S3 took that line
	 * away; at 0 - nothing was ever fetched, so there is no clock to print - the third sentence is simply absent,
	 * rather than stamping a dash where a time belongs.
	 *
	 * <p>It never answers "": the first two sentences are true before any fetch and after every one, so this line
	 * always has something to say and the label always has a tooltip.
	 *
	 * @param pricesAtMillis when the guide prices on screen were read; 0 for "never fetched"
	 */
	static String updateTooltip(long pricesAtMillis)
	{
		return updateTooltip(pricesAtMillis, null);
	}

	/**
	 * {@link #updateTooltip(long)} under the view switches (addendum T, line T5). With {@code livePrices} ON the
	 * line above it says something else, so the hover does too:
	 *
	 * <pre>
	 * Actively traded items show the wiki's live traded price, refreshed on Refresh and every 30 minutes; their windows compare against that day's traded average.
	 * Thin items (fewer than 100 traded yesterday, a wide buy/sell gap today or yesterday, or a live price more than 50 % from the guide or from yesterday's average) keep the daily Grand Exchange guide price.
	 * Last checked 09:05.
	 * </pre>
	 *
	 * <p>The guide hover's three source sentences are NOT said here, and that is the point of the branch: "it is
	 * the price shown on the Grand Exchange website" is true of every figure on screen while the switch is off and
	 * false of the moving ones while it is on, so a reader comparing a row against the GE site is told which state
	 * they are in rather than being handed a sentence that was true yesterday. The last line is the same clock in
	 * both, by the same rule - absent at 0, because nothing has been fetched to time.
	 *
	 * @param options the view switches; null reads as the guide-only hover, which is what every caller that knows
	 *                nothing of addendum T wants
	 */
	static String updateTooltip(long pricesAtMillis, @Nullable ViewOptions options)
	{
		final StringBuilder sb = new StringBuilder(480).append("<html>");
		if (options != null && options.livePrices())
		{
			sb.append(UPDATE_LIVE_WHY).append("<br>").append(UPDATE_LIVE_THIN);
		}
		else
		{
			sb.append(UPDATE_WHY).append("<br>").append(UPDATE_SOURCE).append("<br>").append(UPDATE_RECHECK);
		}
		if (pricesAtMillis > 0L)
		{
			sb.append("<br>").append(UPDATE_LAST_CHECKED).append(MovementMath.formatTime(pricesAtMillis)).append('.');
		}
		return sb.append("</html>").toString();
	}

	/**
	 * What the card's last line reads for a set of view switches (T5): {@link #UPDATE_LIVE_TEXT} while
	 * {@code livePrices} is on and {@link #UPDATE_TEXT} - addendum S's sentence, word for word - while it is off.
	 *
	 * <p>The line follows the SWITCH and not the data: it is drawn before the first fetch, with the wiki down and
	 * with every stack in the bank too thin to qualify, and in each of those states "live prices on" is still the
	 * true answer to "why might this figure move when I refresh?". How many stacks actually are live is a count,
	 * and counts live on the rows; since addendum AF the card's hover carries the exact value alone.
	 *
	 * @param options the switches; null reads as {@link ViewOptions#DEFAULT}
	 */
	static String updateText(@Nullable ViewOptions options)
	{
		return (options == null ? ViewOptions.DEFAULT : options).livePrices() ? UPDATE_LIVE_TEXT : UPDATE_TEXT;
	}






	/** "+12,400,000", "-3,100,000", "0": the exact gp move with its sign, as the row tooltip prints one. */
	private static String signedExact(long gp)
	{
		return (gp > 0L ? "+" : "") + MovementMath.formatExact(gp);
	}

	// ---------------------------------------------------------------- the cards (EDT)

	/** Contract C30's state rule; LOGIN until the first publish. */
	private String chooseCard()
	{
		if (status == null)
		{
			return CARD_LOGIN;
		}
		if (!status.loggedIn() && !status.bankLoaded())
		{
			return CARD_LOGIN;
		}
		if (!status.bankLoaded())
		{
			return CARD_NO_BANK;
		}
		return rows.isEmpty() ? CARD_EMPTY : CARD_LIST;
	}

	private void showCard(String name)
	{
		if (CARD_EMPTY.equals(name))
		{
			renderEmptyCard();
		}
		if (!name.equals(card))
		{
			card = name;
			cardLayout.show(cards, name);
		}
	}

	/**
	 * The EMPTY card (N section 3 §6): why the list is empty, and - only when a band did it - the one-tap
	 * "Clear price range" under the message, added and removed like every other conditional row. A user is
	 * never trapped behind a filter.
	 */
	private void renderEmptyCard()
	{
		final boolean noTradeables = status != null && status.bankItems() == 0;
		final boolean band = bandOn();
		if (noTradeables)
		{
			emptyMessage.setContent(NO_TRADEABLES_TEXT, "Only Grand Exchange items have a guide price");
		}
		else if (!band)
		{
			emptyMessage.setContent(NO_PRICES_TEXT, "The list fills when the guide prices arrive");
		}
		else
		{
			emptyMessage.setContent(EMPTY_TEXT, bandDescription(filter.gpMin(), filter.gpMax()));
		}
		final boolean wantButton = band && !noTradeables;
		if (wantButton != (clearBandRow.getParent() == emptyColumn))
		{
			if (wantButton)
			{
				emptyColumn.add(clearBandRow);
			}
			else
			{
				emptyColumn.remove(clearBandRow);
			}
			emptyColumn.revalidate();
			emptyColumn.repaint();
		}
	}

	/** Which card is showing: {@link #CARD_LOGIN}, {@link #CARD_NO_BANK}, {@link #CARD_EMPTY} or {@link #CARD_LIST}. */
	public String card()
	{
		return card;
	}

	// ---------------------------------------------------------------- for the bridge and the tests

	/**
	 * What a picture of this sidebar is made of, top to bottom: the pinned header, then the SCROLLED CONTENT
	 * (the scroll pane's view, however tall) rather than the window onto it - printing the panel itself stops
	 * where the visible slice stops (Loot and Beam's first live shot, cut through the middle). While a
	 * message card is showing, that card stands in for the list.
	 */
	public List<Component> shotComponents()
	{
		final Component body;
		switch (card)
		{
			case CARD_LOGIN:
				body = loginCard;
				break;
			case CARD_NO_BANK:
				body = noBankCard;
				break;
			case CARD_EMPTY:
				body = emptyCard;
				break;
			default:
				body = listView;
		}
		return Arrays.asList(header, body);
	}

	/**
	 * One line of JSON describing the visible state, for the bridge's {@code state}: the card, the paging,
	 * the filter, the two fields' validity, the status sentence and whether the panel is on screen, with the bank
	 * hold beside it ({@code bank}, addendum AS: {@code open}, {@code pending}, {@code glow}, {@code heldEvents},
	 * {@code reads} - see {@link #bankJson}) - then the
	 * five view switches ({@code options}, Q7, T1, Y1 and AH - {@code holding} is gone with addendum AO),
	 * beside them what the TRADED feeds delivered for this
	 * publish ({@code live}, T8: {@code fetchedAt}, {@code latestItems}, {@code liveRows}, {@code guideRows},
	 * {@code alchRows}, every figure 0 while the switch is off, then addendum U's {@code liveDay} and
	 * {@code windowDays} - the live calendar this publish counted back from, both null-valued while there is no
	 * snapshot), and the three hero switches ({@code hero}, O3),
	 * each written from its own value's {@code asMap()} in the same shape, the hero as drawn (the old {@code bankMove}
	 * line synthesised in full for addendum M's scripts; {@code bankValue}, {@code heroGp} and {@code heroPct}
	 * are "" while their figure is hidden), the card's update line ({@code updateLine}, S4 - beside
	 * {@code heroSub} because it is the line under it, and always the same sentence, so a script can prove it is
	 * drawn without a picture), the control row - {@code sortLabel}, the lit COLUMN's label as the button prints
	 * it (addendum W, line W3; the direction is beside it in {@code descending} and drawn as the arrow, and V2's
	 * {@code sortHint} is gone with the grey qualifiers it described) - the fold, the problem row and the
	 * Show-more text (N section 3 §8).
	 *
	 * <p>The fold carries the reader's quick bands since addendum Z (line Z4): {@code presets}, the three in force
	 * as numbers ({@code [100000,1000000,10000000]}), and {@code presetLabels}, the four chip TEXTS as they are
	 * drawn ({@code ["All","100k+","1m+","10m+"]}) - the numbers so a script can assert what was stored, the
	 * labels so it can assert what a shot of the fold should read, without a picture.
	 */
	public String describe()
	{
		final PortfolioSummary summary = portfolio();
		final WindowMove move = summary.move(filter.window());
		return "{\"card\":\"" + card
			+ "\",\"shown\":" + shown
			+ ",\"total\":" + rows.size()
			+ ",\"window\":\"" + filter.window().name()
			+ "\",\"sort\":\"" + filter.sort().name()
			+ "\",\"descending\":" + filter.descending()
			+ ",\"gpMin\":" + filter.gpMin()
			+ ",\"gpMax\":" + filter.gpMax()
			+ ",\"minInvalid\":" + Widgets.isMarkedInvalid(minField)
			+ ",\"maxInvalid\":" + Widgets.isMarkedInvalid(maxField)
			+ ",\"status\":" + json(statusText())
			+ ",\"showing\":" + isShowing()
			+ ",\"bank\":" + bankJson()
			+ ",\"options\":" + flags(options.asMap())
			+ ",\"live\":" + values(liveStatus().asMap())
			+ ",\"hero\":" + flags(heroVisibility.asMap())
			+ ",\"bankValueShowing\":" + heroShowing()
			+ ",\"bankValue\":" + json(shows(totalLabel) ? totalLabel.getText() : "")
			+ ",\"bankMove\":" + json(moveText(filter.window(), move))
			+ ",\"bankWindow\":\"" + filter.window().label()
			+ "\",\"heroGp\":" + json(shows(deltaLabel) ? deltaLabel.getText() : "")
			+ ",\"heroPct\":" + json(shows(pctLabel) ? pctLabel.getText() : "")
			+ ",\"heroSub\":" + json(heroSubText())
			+ ",\"updateLine\":" + json(updateLabel.getText())
			+ ",\"sortLabel\":" + json(sortButton.getText())
			+ ",\"countText\":" + json(bandTarget.getText())
			+ ",\"bandOn\":" + bandOn()
			+ ",\"foldOpen\":" + foldOpen
			+ ",\"presets\":" + numbers(presets.mins())
			+ ",\"presetLabels\":" + texts(presetLabels())
			+ ",\"problemText\":" + json(problemText())
			+ ",\"problemRed\":" + isErrorStatus()
			+ ",\"showMoreText\":" + json(showMoreVisible() ? showMoreLabel.getText() : "")
			+ "}";
	}

	/**
	 * {@code describe()}'s {@code bank} object (addendum AS, section 7.3): the plugin's four values as this panel last
	 * mirrored them - {@code open}, {@code pending}, {@code heldEvents}, {@code reads} - with {@code glow}, whether the
	 * ring is actually lit, between them. It sits beside {@code showing} because the two are what decide whether
	 * a publish is built, and it is how a live run proves the plan's claims without a picture: one read per visit,
	 * events held while pending, the ring lit exactly while it should be.
	 */
	private String bankJson()
	{
		return "{\"open\":" + bankOpen
			+ ",\"pending\":" + bankPending
			+ ",\"glow\":" + glowRunning()
			+ ",\"heldEvents\":" + bankHeldEvents
			+ ",\"reads\":" + bankReads
			+ "}";
	}

	/** The line under the figures as drawn: the provenance footnote. */
	String heroSubText()
	{
		return footnoteLabel.getText();
	}

	/**
	 * What the traded feeds delivered for the publish on screen (addendum T, line T8), for {@code describe()}:
	 * the last status's {@link Status.LiveStatus}, and {@link Status.LiveStatus#OFF} - every figure 0, every day
	 * null - before the first publish, so {@code state.live} is an object with the same keys in every state a
	 * script can find the panel in.
	 */
	private Status.LiveStatus liveStatus()
	{
		final Status.LiveStatus live = status == null ? null : status.live();
		return live == null ? Status.LiveStatus.OFF : live;
	}

	/**
	 * A JSON object of booleans, in the map's own order - the three hero switches, from
	 * {@link HeroVisibility#asMap()}, which is the same object the dev bridge's {@code state.hero} is built from
	 * (S2). Written by hand rather than with Gson: this panel is Swing and has no injected Gson, and the bridge's
	 * one is not the panel's to reach for.
	 */
	private static String flags(Map<String, Boolean> values)
	{
		final StringBuilder sb = new StringBuilder(48).append('{');
		for (Map.Entry<String, Boolean> e : values.entrySet())
		{
			if (sb.length() > 1)
			{
				sb.append(',');
			}
			sb.append(json(e.getKey())).append(':').append(Boolean.TRUE.equals(e.getValue()));
		}
		return sb.append('}').toString();
	}

	/**
	 * {@link #flags} for {@link Status.LiveStatus#asMap()} - the five figures of T8 and addendum U's two calendar
	 * keys, which together are what {@code state.live} is. Written by hand for the same reason: this panel is Swing
	 * and has no injected Gson.
	 *
	 * <p>Four value shapes, which is every shape that map holds: a {@link Number} prints as a number, a
	 * {@link String} (an ISO date) as a quoted string, a nested {@link Map} as a nested object - one level, which
	 * is all {@code windowDays} is - and a null as JSON {@code null}, because "that window has no traded bucket" is
	 * a fact and not a zero. Anything else would be a new key nobody has taught this to write, so it is refused
	 * rather than printed as its {@code toString}.
	 */
	private static String values(Map<?, ?> map)
	{
		final StringBuilder sb = new StringBuilder(160).append('{');
		for (Map.Entry<?, ?> e : map.entrySet())
		{
			if (sb.length() > 1)
			{
				sb.append(',');
			}
			sb.append(json(String.valueOf(e.getKey()))).append(':').append(value(e.getValue()));
		}
		return sb.append('}').toString();
	}

	/** A JSON array of numbers - the three preset bands, smallest first ({@code presets}, Z4). */
	private static String numbers(long[] values)
	{
		final StringBuilder sb = new StringBuilder(40).append('[');
		for (int i = 0; i < values.length; i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append(values[i]);
		}
		return sb.append(']').toString();
	}

	/** A JSON array of strings - the fold's four chip texts ({@code presetLabels}, Z4). */
	private static String texts(String[] values)
	{
		final StringBuilder sb = new StringBuilder(40).append('[');
		for (int i = 0; i < values.length; i++)
		{
			if (i > 0)
			{
				sb.append(',');
			}
			sb.append(json(values[i]));
		}
		return sb.append(']').toString();
	}

	/** One JSON value for {@link #values}: a number, a quoted string, a nested object, or {@code null}. */
	private static String value(@Nullable Object v)
	{
		if (v == null)
		{
			return "null";
		}
		if (v instanceof Number)
		{
			return v.toString();
		}
		if (v instanceof Map)
		{
			return values((Map<?, ?>) v);
		}
		if (v instanceof String)
		{
			return json((String) v);
		}
		throw new IllegalArgumentException("no JSON shape for " + v.getClass().getSimpleName());
	}

	private static String json(@Nullable String s)
	{
		if (s == null)
		{
			return "null";
		}
		final StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
		for (int i = 0; i < s.length(); i++)
		{
			final char c = s.charAt(i);
			switch (c)
			{
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
			}
		}
		return sb.append('"').toString();
	}

	/** The rows built so far, in list order - the bridge echoes the first ten. */
	public List<MovementRowPanel> rowPanels()
	{
		final List<MovementRowPanel> out = new ArrayList<>(rowsColumn.getComponentCount());
		for (Component c : rowsColumn.getComponents())
		{
			if (c instanceof MovementRowPanel)
			{
				out.add((MovementRowPanel) c);
			}
		}
		return out;
	}

	@Nullable
	public Status status()
	{
		return status;
	}

	/** How many times the list was rebuilt - the tests' proof that a status-only publish leaves it alone. */
	int rebuilds()
	{
		return rebuilds;
	}

	/** The baseline day the rows on screen carry in their tooltips (L7); null until one is known. */
	@Nullable
	LocalDate listThenDay()
	{
		return list.thenDay;
	}

	JPanel header()
	{
		return header;
	}

	/** The hero card, whether or not it is in the header right now. */
	JPanel hero()
	{
		return hero;
	}

	/** Whether the hero card is in the header - true exactly while a bank is loaded (M4, N section 3 §3). */
	boolean heroShowing()
	{
		return hero.getParent() == header;
	}

	/**
	 * Whether {@code c} is drawn on the hero card right now - which of the card's parts are IN it (O3), asked
	 * once instead of a named accessor per part restating {@link #syncHero}'s rules.
	 *
	 * <p>The walk up the parent chain is what makes it one question: the total and the move line are children of
	 * the card, the gp figure and the percentage are children of the move LINE, and a switched-off part is
	 * removed rather than hidden - so a part is drawn exactly when every link above it is still in place.
	 * Whether the CARD itself is in the header is a different question ({@link #heroShowing()}): the switches
	 * hold their answer while there is no bank to draw.
	 */
	boolean shows(@Nullable Component c)
	{
		return c != null && SwingUtilities.isDescendingFrom(c, hero);
	}

	/** The gear menu: Refresh, the three show / hide check items (O4) and the four view check items (Q2, T1, Y1). */
	JPopupMenu heroMenu()
	{
		return heroMenu;
	}

	/** The menu's "Use live prices" check item, first of the view group (T1, renamed by Y4). */
	JCheckBoxMenuItem livePricesItem()
	{
		return livePricesItem;
	}

	/** The gear on the total's line, which opens {@link #heroMenu()} (Q1). */
	JLabel gearLabel()
	{
		return gearLabel;
	}

	/** The total's line: the figure while it is shown, and the gear always (Q1). */
	JPanel totalRow()
	{
		return totalRow;
	}

	/** The menu's "Include coins and platinum tokens" check item (Q2, renamed by Y4). */
	JCheckBoxMenuItem countCashItem()
	{
		return countCashItem;
	}

	/** The menu's "Include untradeable items" check item (Q2, renamed by Y4). */
	JCheckBoxMenuItem countUntradeablesItem()
	{
		return countUntradeablesItem;
	}

	/** The menu's "Include inventory and worn gear" check item (Y1). */
	JCheckBoxMenuItem countInventoryItem()
	{
		return countInventoryItem;
	}

	/** The menu's box row: the "Preset price ranges" caption over the three boxes (Z2, Z7, AB3). */
	JPanel presetRow()
	{
		return presetRow;
	}

	/**
	 * Where the keyboard goes when the gear menu opens (Z6): the preset {@link #presetRow() row}, and never one of
	 * the boxes in it.
	 *
	 * <p>It is named rather than left implicit because the rule is a promise to the reader - no caret blinks until
	 * a box is clicked - and the only thing that can be seen from outside is WHICH component the open event asks
	 * for. Focus itself belongs to a shown window, which a test has none of.
	 */
	Component menuFocusTarget()
	{
		return presetRow;
	}

	/** One of those boxes, {@code i} counted smallest first (Z2). */
	Widgets.PlaceholderField presetField(int i)
	{
		return presetFields[i];
	}

	/** AH: the gear's hover switch, the last ITEM in the menu, in the row above the bottom one. */
	JMenuItem showHoverTextItem()
	{
		return showHoverTextItem;
	}

	/** "Reset to default" (Z2), a button at the LEFT end of the menu's last row since AH2. */
	JButton resetPresetsButton()
	{
		return resetPresetsButton;
	}


	/** The menu's last row: the glue and the "OK" button at its right end (AB2). */
	JPanel okRow()
	{
		return okRow;
	}

	/** That button - commit and close (AB2). */
	JButton okButton()
	{
		return okButton;
	}

	/** The menu's "Show bank value" check item (O4). */
	JCheckBoxMenuItem showValueItem()
	{
		return showValueItem;
	}

	/** The menu's "Show change in gp" check item (O4, renamed by Y4). */
	JCheckBoxMenuItem showGpItem()
	{
		return showGpItem;
	}

	/** The menu's "Show change in %" check item (O4, renamed by Y4). */
	JCheckBoxMenuItem showPctItem()
	{
		return showPctItem;
	}

	/** The caption row: "Bank value" and the "Refresh" link; never hides (O3). */
	JPanel captionRow()
	{
		return captionRow;
	}

	JLabel captionLabel()
	{
		return captionLabel;
	}

	/** The "Refresh" text link in the caption row. */
	JLabel refreshLabel()
	{
		return refreshLabel;
	}

	/** The bank total, 28 px bold white, stack form ("1.23b"); in the card while {@code value} is on. */
	JLabel totalLabel()
	{
		return totalLabel;
	}

	/** The move line - triangle, gp, percent - in the card while {@code gp} or {@code pct} is on. */
	JPanel moveLine()
	{
		return moveLine;
	}

	/** The lit window's percentage, 18 px bold in the move colour; on the move line while {@code pct} is on. */
	JLabel pctLabel()
	{
		return pctLabel;
	}

	/** The lit window's gp move, 18 px bold in the move colour; on the move line while {@code gp} is on. */
	JLabel deltaLabel()
	{
		return deltaLabel;
	}

	/** The move triangle; on the move line whenever the line is. */
	JLabel triangleLabel()
	{
		return triangleLabel;
	}

	/** The holder of the window strip inside the card; never hides (O3). */
	JPanel stripHolder()
	{
		return stripHolder;
	}

	/** The provenance footnote; never hides (O3). */
	JLabel footnoteLabel()
	{
		return footnoteLabel;
	}

	/** The card's last line - {@link #UPDATE_TEXT} under the footnote; never hides (S1). */
	JLabel updateLabel()
	{
		return updateLabel;
	}

	/** The window strip inside the card. */
	JPanel chipRow()
	{
		return chipRow;
	}

	JLabel windowChip(MovementWindow window)
	{
		return windowChips.get(window);
	}

	JPanel controlRow()
	{
		return controlRow;
	}

	JLabel sortButton()
	{
		return sortButton;
	}

	/** The band word-button ("All items v"). */
	JLabel bandTarget()
	{
		return bandTarget;
	}

	JPanel fold()
	{
		return fold;
	}

	boolean foldOpen()
	{
		return foldOpen;
	}

	/** The fold's chip {@code i}: "All", then the three presets smallest first (Z3). */
	JLabel presetCell(int i)
	{
		return presetCells[i];
	}

	/** The fold's "x". */
	JLabel clearBoundsLabel()
	{
		return clearLabel;
	}

	Widgets.PlaceholderField minField()
	{
		return minField;
	}

	Widgets.PlaceholderField maxField()
	{
		return maxField;
	}

	JLabel problemLabel()
	{
		return problemLabel;
	}

	boolean problemShowing()
	{
		return problemLabel.getParent() == header;
	}

	/** The "Show n more" text control at the foot of the list. */
	JLabel showMoreLabel()
	{
		return showMoreLabel;
	}

	boolean showMoreVisible()
	{
		return showMoreRow.getParent() == listColumn;
	}

	/** The EMPTY card's "Clear price range" button, whether or not it is on the card right now. */
	JButton clearBandButton()
	{
		return clearBandButton;
	}

	boolean clearBandShowing()
	{
		return clearBandRow.getParent() == emptyColumn;
	}

	JPanel rowsColumn()
	{
		return rowsColumn;
	}

	JScrollPane scrollPane()
	{
		return scroll;
	}

	JPanel listView()
	{
		return listView;
	}

	boolean stopped()
	{
		return stopped;
	}
}
