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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
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
 * {@link ItemManager} (each sprite is a drawn {@link MovementRowPanel#ICON_WIDTH} x {@link MovementRowPanel#ICON_HEIGHT}
 * placeholder in a colour of the item's own) and a mocked
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
 * (measured 2026-09-11: with it, this class reproduces {@code ticker-2026-09-20-AK.png} and
 * {@code ticker-hidden-2026-09-20-AK.png} to the byte; without it, neither matches while the picture is the
 * same), so a comparison against them has to be taken the same way.
 *
 * <p><b>The current pins are {@code docs/handoff/lab/ticker-{,hidden-,options-,live-,amounts-hidden-}2026-10-04-I.png} and
 * {@code history{,-one,-empty,-hidden,-amounts-hidden}-2026-10-04-I.png}</b> (1.1.0 part I, the eye beside Discord): the G
 * pictures for Items and the E pictures for History with the "Hide amounts" eye moved out of the total's row - where it stood
 * left of the Refresh link - into the card's top-right icons, first of three (eye, Discord, settings), and drawn in grey 52
 * while the amounts show and grey 165 while hidden. Each differs from the pin it follows only inside the eye's old box
 * ({@link #oldEyeIcon}) and its new one ({@link #eyeIcon}), which {@code ViewStripPicturesTest} and
 * {@code HistorySidebarPicturesTest} measure against the kept files themselves.
 *
 * <p><b>The G pins are {@code docs/handoff/lab/ticker-{,hidden-,options-,live-,amounts-hidden-}2026-10-04-G.png}</b>
 * (1.1.0 part G, "List options"): the E pictures with the search row's right end changed - the box shortened by the two
 * gears' 12 px and their 6 px gap, the gears in the placeholder grey - and nothing else, which {@code ViewStripPicturesTest}
 * measures against the E files themselves. The options picture is drawn with the tick "Show alch-only items" ON, because it
 * is the picture that holds the two alch rows ({@link #build}'s prefs answer the tick as the switch that puts them in the
 * fixture), so it still shows them. The History pins stay the E ones below: the History tab has no search row.
 *
 * <p><b>The E pins are {@code docs/handoff/lab/ticker-{,hidden-,options-,live-}2026-10-04-E.png} and
 * {@code history{,-one,-empty,-hidden}-2026-10-04-E.png}</b> (1.1.0 part E, "Hide amounts"): the eye beside the Refresh
 * link in the card's number row (until part I moved it), with the amounts SHOWN - every other pixel of every picture is what it was, which
 * {@code ViewStripPicturesTest} and {@code HistorySidebarPicturesTest} measure against the previous pins (the C pictures
 * for Items, the D pictures for History, the B one for the empty History) as "identical outside the eye's 12 x 12 box".
 * Two more pictures are pinned as {@code ticker-amounts-hidden-2026-10-04-E.png} and
 * {@code history-amounts-hidden-2026-10-04-E.png}: the same two sidebars with the eye shut - every amount and quantity
 * masked, the picture of each item without its stack number.
 *
 * <p>The previous pins stay on disk as history. <b>The C pins</b> ({@code ticker-{,hidden-,options-,live-}2026-10-01-C.png},
 * 1.0.9 part 4, the search box): the B pictures below with the box above the first row - {@link #SEARCH_ROW_HEIGHT} px of
 * header inserted under the fold, everything under it that much lower, the pictures that much taller, and nothing else
 * changed, which {@code ViewStripPicturesTest} measures against the B files themselves. The History pictures have no
 * search box and are untouched by it.
 *
 * <p><b>The D pins</b> ({@code history{,-one,-hidden}-2026-10-04-D.png}, 1.1.0 part D): "Single chart colour" turned ON by
 * default, the chart in them the logo gold. The empty History picture draws no chart and stayed the B picture until the
 * eye. The B pins of the other three ({@code ...-2026-09-30-B.png}) are kept on disk: they are what this class drew with
 * the switch OFF before the eye, which {@code HistorySidebarPicturesTest} still proves, apart from the eye's box. The four
 * Items pictures show no chart.
 *
 * <p>The B pins before them ({@code ticker-{,hidden-,options-,live-}2026-09-30-B.png}, 1.0.9, the support pair): the AU
 * pictures below with the Discord mark beside the settings icon in the card's top row and the Refresh link moved down
 * to the number's row - the card's top two rows and nothing else, every pixel row from the number row down and every
 * row above the card identical, which {@code ViewStripPicturesTest} measured against the AU files themselves. The
 * History pictures ({@link #HISTORY_FILE} and its three) moved the same way and were pinned as B (three of them are
 * pinned as D since 1.1.0 part D, the B files kept).
 *
 * <p>The AU pins before them ({@code ticker-{,hidden-,options-,live-}2026-09-28-AU.png}, addendum AU): the AV pictures
 * with the Items | Net Worth History strip inserted under the card - everything under it {@link #STRIP_HEIGHT} px lower,
 * the pictures that much taller, and nothing else changed, which {@code ViewStripPicturesTest} measures against the AV
 * files themselves. They stay as history, like every pin here.
 *
 * <p>The AV pins before them ({@code ticker-{,hidden-,options-,live-}2026-09-28-AV.png}, addendum AV): the default
 * fixture gained its Crystal body, so the ticker, hidden and live pictures are the AR ones with that row inserted
 * above the row with no baseline - everything under it 64 px lower, the picture then 1145 px - and, where the card
 * shows it, the total 16,694,766 gp higher and the move the body's own -1,605,234 gp lower on every window
 * ({@link #summary(ViewOptions)}); the options picture already held that row, so it differs from
 * {@code ticker-options-2026-09-21-AR.png} in the card's move line alone. The AR and AV pictures stay as history.
 *
 * <p><b>The whole sidebar in History</b> (addendum AU) is drawn by {@link #renderHistory(HeroVisibility,
 * BankHistorySeries)}: the same panel, opened on History with the 30d chip lit ({@link #HISTORY_WINDOW}), over
 * {@link HistoryViewRenderer}'s 40-day fixture - the ONE History fixture, which the view's own pictures are drawn
 * over too - with the card's total that fixture's last reading ({@link HistoryViewRenderer#LAST_GP}) and the panel's
 * clock local noon of its last day ({@link #historyNowMillis()}). Four pictures are pinned in
 * {@code docs/handoff/lab/}: {@link #HISTORY_FILE}, {@link #HISTORY_ONE_FILE}, {@link #HISTORY_EMPTY_FILE} and
 * {@link #HISTORY_HIDDEN_FILE}; {@code HistorySidebarPicturesTest} proves each is what this class draws and that the
 * card and the strip stand exactly where they stand in the Items pictures. Each is exactly as tall as its content
 * plus {@link #HISTORY_GROUND} ({@link #historyHeight}), so no picture carries a scroll bar.
 */
public final class LookRenderer
{
	/** The sidebar's width: {@link PluginPanel#PANEL_WIDTH}. */
	public static final int WIDTH = PluginPanel.PANEL_WIDTH;
	/**
	 * Tall enough for the header, the twelve-row fixture and the foot without a scroll bar - 225 x 900 when a row
	 * was 48 px (N5), and 225 x 1080 since the Q4 row format made it 62 (addendum AN). A row's pitch is
	 * {@link MovementRowPanel#ROW_HEIGHT} plus the 3 px gutter between cards, so twelve of them went from 612 px
	 * to 780 and the picture had to find 168 somewhere; left at 900 the list grew a SCROLL BAR, which narrows the
	 * column under the header and squeezes every row card, so the pictures would no longer be the sidebar.
	 *
	 * <p>It is deliberately a little more than the 168: the ground under the last row is where a reader sees that
	 * the list ended rather than ran out, and {@code everyPictureIsTallEnoughForItsListWithTheFoldOpen} fails
	 * loudly if this is ever short again.
	 *
	 * <p>One row pitch taller again since addendum AV (1080 -> 1145): the default fixture gained its Crystal body,
	 * a thirteenth row, because an untradeable made from tradeable parts now counts with the untradeables switch
	 * off. Left at 1080 the list grew the scroll bar this constant exists to prevent. The options picture already
	 * held that row, so {@link #OPTIONS_HEIGHT_CLOSED} gives the pitch back and stays the size it was.
	 *
	 * <p>And {@link #STRIP_HEIGHT} taller since addendum AU (1145 -> 1189): the Items | Net Worth History strip went
	 * into the header under the card, and the picture grows by exactly that so everything under the strip is the AV
	 * picture moved down and nothing else - the list keeps its height and the ground under its last row stays what it
	 * was (measured, the column's 6 px bottom margin included: 62 px on the ticker and live pictures, 107 on the hidden
	 * one, 121 on the options one).
	 *
	 * <p>And {@link #SEARCH_ROW_HEIGHT} taller again since 1.0.9 part 4 (1189 -> 1209), for the same reason and by the
	 * same rule: the search box went into the header above the first row, and the picture grows by exactly what the header
	 * did, so the list keeps its height and the ground under its last row stays what it was.
	 */
	public static final int HEIGHT = 1080 + MovementRowPanel.ROW_HEIGHT + 3 + LookRenderer.STRIP_HEIGHT
		+ LookRenderer.SEARCH_ROW_HEIGHT;
	/**
	 * The Items | Net Worth History strip's own height at this width, MEASURED in Swing (addendum AU; the contract's
	 * section 10): the {@link BankPriceMovementPanel#ROW_GAP} of air under the card, the {@link Widgets#TOGGLE_HEIGHT}
	 * px toggle and its one-line caption - which is the distance everything under the card moved when the strip went
	 * in. Pinned as {@link #FOLD_HEIGHT} is, by {@code ViewStripPicturesTest}: a strip that grew fails there rather
	 * than quietly squeezing the picture. The mock generator gave 40 px; the Swing strip measures 44 (read off the
	 * painted picture as well: 6 px of air under the card, the 20 px toggle with its 1 px frame, 3 px, and the
	 * caption's 15 px line), and the control row under it keeps its own 6 px gap.
	 */
	public static final int STRIP_HEIGHT = 44;
	/**
	 * What the search box added to the header, MEASURED in Swing (1.0.9 part 4): {@link BankPriceMovementPanel#SEARCH_GAP}
	 * of air over a box as tall as the Min / Max boxes - 16 px, the field's own preferred height - which is the distance
	 * everything under the fold moved when the box went in. The air UNDER the box is not part of it: that 4 px is the
	 * header's own bottom padding, which stood between the fold and the first row before the box did and still does, so
	 * the box stands 4 px over the first row and the row has moved by the box and the air over it and no more. Pinned as
	 * {@link #STRIP_HEIGHT} is, by {@code ViewStripPicturesTest}: a row that grew fails there rather than quietly squeezing
	 * the picture.
	 */
	public static final int SEARCH_ROW_HEIGHT = 20;
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
	public static final int OPTIONS_HEIGHT_CLOSED = HEIGHT + 2 * (MovementRowPanel.ROW_HEIGHT + 3);
	/**
	 * The options picture's height (R5, and {@link #FOLD_HEIGHT} taller again since AA3). Its fixture carries
	 * TWO rows the other three pictures do not - addendum Q's two untradeable stacks; addendum R's one valued at its
	 * parts is in every picture since addendum AV - so it is two row pitches taller than {@link #HEIGHT} (three
	 * before AV, when {@link #HEIGHT} was a pitch shorter: the same 1332 px either way, and 1376 since the AU strip
	 * went into {@link #HEIGHT}), a pitch being the card plus
	 * the 3 px gutter between cards. It was one pitch taller until addendum AN: at 48 px a row, {@link #HEIGHT}
	 * had slack for two more rows and the arithmetic was never exercised; at 62 px it is, and a picture that is
	 * short brings a scroll bar that narrows the list under the header and squeezes every 213 px row card.
	 *
	 * <p><b>The open fold costs the list the same thing</b> (AA3): the header grew by {@link #FOLD_HEIGHT} and the
	 * list under it lost exactly that, and this was the one picture with no room to give - it ended on 7 px of bare
	 * ground at the time. Left alone it grew a scroll bar, which is the failure R5 describes, so it is given back what
	 * the fold took. The other three pictures were NOT given it: they kept the {@link #HEIGHT} they are compared at,
	 * and the fold simply ate 57 px of the ground under their last row (the ticker and live pictures end on 62 px of
	 * it today, the hidden one on 107, the options picture on 121 - measured with the AU strip in, which
	 * {@link #HEIGHT} pays for).
	 *
	 * <p>{@link #HEIGHT} is normally untouched on purpose - the acceptance shots are compared BYTE FOR BYTE
	 * against the pictures the addendum before left behind, and a picture of another size cannot be - but
	 * addendum AN is one of the rare changes that moves it, because the rows themselves changed height.
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
	/**
	 * What {@link #OPTIONS_FILE} is drawn with: untradeables counted (Q7), live off (T8). It asked for
	 * holdings on the rows as well until addendum AO deleted that switch - since addendum AN every row shows
	 * the stack AND the item whatever is set, so the picture is unmoved by its going.
	 */
	public static final ViewOptions OPTIONS = ViewOptions.DEFAULT.withCountUntradeables(true).withLivePrices(false);
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
	/**
	 * How many of the live picture's thirteen stacks are left on the guide price: nine of the twelve, and the
	 * Crystal body at its guide-priced parts since addendum AV.
	 */
	static final int LIVE_GUIDE_STACKS = 10;
	/** How many items the live picture's {@code /latest} snapshot names (T8's {@code latestItems}). */
	static final int LIVE_LATEST_ITEMS = 4_535;

	private LookRenderer()
	{
	}

	/**
	 * Writes the four Items pictures and, since addendum AU, the four History ones into {@code args[0]}, or
	 * {@code build/} when no directory is given.
	 */
	public static void main(String[] args) throws Exception
	{
		final File dir = new File(args.length > 0 ? args[0] : "build");
		System.out.println(write(dir, TICKER_FILE, HeroVisibility.ALL).getAbsolutePath());
		System.out.println(write(dir, HIDDEN_FILE, HeroVisibility.NONE).getAbsolutePath());
		System.out.println(write(dir, OPTIONS_FILE, HeroVisibility.ALL, OPTIONS).getAbsolutePath());
		System.out.println(write(dir, LIVE_FILE, HeroVisibility.ALL, LIVE).getAbsolutePath());
		System.out.println(writeHistory(dir, HISTORY_FILE, HeroVisibility.ALL, HistoryViewRenderer.fixture())
			.getAbsolutePath());
		System.out.println(writeHistory(dir, HISTORY_ONE_FILE, HeroVisibility.ALL, HistoryViewRenderer.oneReading())
			.getAbsolutePath());
		System.out.println(writeHistory(dir, HISTORY_EMPTY_FILE, HeroVisibility.ALL, BankHistorySeries.EMPTY)
			.getAbsolutePath());
		System.out.println(writeHistory(dir, HISTORY_HIDDEN_FILE, HeroVisibility.NONE, HistoryViewRenderer.fixture())
			.getAbsolutePath());
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
	 * the untradeable switch has put two more rows in the list than {@link #HEIGHT} holds (R5).
	 */
	public static int height(ViewOptions options)
	{
		return height(options, FOLD_OPEN);
	}

	/**
	 * {@link #height(ViewOptions)} for a chosen fold state (AA3). The three twelve-row pictures are unmoved by the
	 * fold - it eats 57 px of the bare ground under their last row, and they keep 62 px of it or more with the fold
	 * open - so only the options picture has two heights, and its closed one is the picture addendum W left behind.
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
	 * <p>The fixture follows the switches the way the service would (Q5, R3, AV): the parts-priced Crystal body is
	 * in the published rows and in the summary under every switch since addendum AV; with {@code countUntradeables}
	 * on, the two alch stacks are too, and with it off neither the list nor the sums has ever heard of them. The panel is handed the same switches through its
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
		return build(shown, view, foldOpen, RowFilter.DEFAULT, SidebarView.ITEMS, status(view), NOW_MILLIS);
	}

	/**
	 * The one road every picture's panel is built by: the real panel over the mocked manager and service, the service
	 * answering {@code status} and the rows for {@code options}, and prefs answering the filter, the card's
	 * visibility, the switches and the fold - which is how the client starts up on stored settings - then the panel's clock
	 * pinned to {@code nowMillis} and the panel switched to {@code showing} as the toggle would.
	 */
	private static BankPriceMovementPanel build(HeroVisibility shown, ViewOptions options, boolean foldOpen,
		RowFilter filter, SidebarView showing, PriceService.Status status, long nowMillis)
	{
		final ViewOptions view = options == null ? GUIDE_ONLY : options;
		final ItemManager itemManager = mock(ItemManager.class);
		when(itemManager.getImage(anyInt(), anyInt(), anyBoolean()))
			.thenAnswer(invocation -> sprite(invocation.getArgument(0)));
		final PriceService service = mock(PriceService.class);
		when(service.currentStatus()).thenReturn(status);
		when(service.currentRows()).thenReturn(rows(view));
		final BankPriceMovementPanel.Prefs prefs = new BankPriceMovementPanel.Prefs()
		{
			@Override
			public RowFilter load()
			{
				return filter;
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

			// 1.1.0 part G: the list shows its alch rows exactly when the fixture holds them - the options picture's two - so
			// every picture lists what it always listed. (The shipped default is off; the tick is not a picture's business.)
			@Override
			public Boolean loadShowAlchRows()
			{
				return view.countUntradeables();
			}
		};
		final BankPriceMovementPanel panel = new BankPriceMovementPanel(itemManager, service, prefs);
		// The picture must be the same one whenever it is rendered, so "today" is the fixture's own day and not
		// the day the suite happens to run: the footnote stamps a bank captured on ANOTHER day with its date
		// rather than a clock (B044), and the History card and view count their days from it (AU).
		panel.setClock(() -> nowMillis);
		// The sidebar opens on Items; a History picture switches to it as the toggle would.
		panel.setView(showing);
		return panel;
	}

	/** The fixture's "now": a minute after the bank was captured - what every Items picture's panel clock reads. */
	static final long NOW_MILLIS = BANK_AT_MILLIS + 60_000L;

	// ---------------------------------------------------------------- the whole sidebar in History (addendum AU)

	/** The History pictures, by the names they are pinned under in {@code docs/handoff/lab/}. */
	public static final String HISTORY_FILE = "history-2026-10-04-I.png";
	/** Day one: the one reading, today's (plan 7.5 item 3: the one point, its readout, its row, no sentence). */
	public static final String HISTORY_ONE_FILE = "history-one-2026-10-04-I.png";
	/** No reading yet: the card's two History lines a dash each (ruling 9.7), the view its one sentence. */
	public static final String HISTORY_EMPTY_FILE = "history-empty-2026-10-04-I.png";
	/** The 40-day fixture with the card's three figures hidden (O3 in History: the card shrinks exactly as in Items). */
	public static final String HISTORY_HIDDEN_FILE = "history-hidden-2026-10-04-I.png";
	/** The card's lit chip in every History picture: 30d, mock 5's own. */
	public static final MovementWindow HISTORY_WINDOW = MovementWindow.D30;
	/**
	 * The bare ground under the History list, in every History picture: what shows the list ENDED. Each picture is
	 * exactly its content's height plus this ({@link #historyHeight}), so none of them grows a scroll bar - the 40-day
	 * list is 40 rows and is drawn whole; only in the client, where the sidebar is shorter, does it scroll.
	 */
	public static final int HISTORY_GROUND = 24;

	/**
	 * What the panel's clock reads in every History picture: LOCAL noon of {@link HistoryViewRenderer#TODAY}, in the zone
	 * the panel counts its days in ({@code ZoneId.systemDefault()}), so "today" is the fixture's last day wherever the
	 * suite runs (amendment 9.1).
	 */
	static long historyNowMillis()
	{
		return HistoryViewRenderer.TODAY.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	/**
	 * EDT. The sidebar opened on History over {@code series}: the panel is switched to {@link SidebarView#HISTORY} and the
	 * prefs answer {@link #HISTORY_WINDOW} as the filter's window, the status is {@link #historyStatus}, and the
	 * clock {@link #historyNowMillis()}. The Items rows are the Items pictures' own, built behind the History card.
	 */
	static BankPriceMovementPanel buildHistory(HeroVisibility shown, BankHistorySeries series)
	{
		return build(shown, GUIDE_ONLY, FOLD_OPEN, RowFilter.DEFAULT.withWindow(HISTORY_WINDOW), SidebarView.HISTORY,
			historyStatus(series), historyNowMillis());
	}

	/**
	 * The Items pictures' status with the History card's own facts: the bank value is the fixture's last reading
	 * ({@link HistoryViewRenderer#LAST_GP}) - so the card's headline and the reading it is compared with are one figure,
	 * as they are in the client, where the reading and the total come from the same computation (amendment 9.6) - the
	 * bank captured a minute before the clock, and {@code series} as its bank history. A MOCK status like the Items one,
	 * so the series is stubbed on it; its {@code options()} answers null, which the card and the view read as the
	 * defaults.
	 */
	static PriceService.Status historyStatus(BankHistorySeries series)
	{
		final PriceService.Status s = status(GUIDE_ONLY);
		final long bankAt = historyNowMillis() - 60_000L;
		when(s.portfolio()).thenReturn(new PortfolioSummary(HistoryViewRenderer.LAST_GP, BANK_ITEMS, BANK_ITEMS, null));
		when(s.bankAtMillis()).thenReturn(bankAt);
		when(s.pricesAtMillis()).thenReturn(bankAt);
		when(s.bankHistory()).thenReturn(series);
		return s;
	}

	/**
	 * EDT. How tall a History picture of {@code panel} is drawn: where the cards start under the header, the History
	 * card's whole content, and {@link #HISTORY_GROUND} - measured by laying the panel out far taller than it needs.
	 */
	static int historyHeight(BankPriceMovementPanel panel)
	{
		panel.setSize(WIDTH, 20_000);
		layoutTree(panel);
		final JScrollPane pane = historyPane(panel);
		final int cardsTop = SwingUtilities.convertPoint(pane.getParent(), pane.getLocation(), panel).y;
		return cardsTop + pane.getViewport().getView().getPreferredSize().height + HISTORY_GROUND;
	}

	/** The History card's scroll pane: the one around the panel's {@link BankHistoryView}. */
	static JScrollPane historyPane(BankPriceMovementPanel panel)
	{
		final Component view = find(panel, BankHistoryView.class);
		return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, view);
	}

	/**
	 * EDT, on a panel already laid out: the 12 x 12 box the "Hide amounts" eye's icon is painted in, in {@code panel}'s own
	 * coordinates (1.1.0 part E) - the label's bounds less its insets, which is where an icon-only label paints a 12 px icon
	 * it is exactly wide and tall enough for. Since part I the eye is in the card's top row, first of the three top-right icons,
	 * and this is that place. The one rectangle a picture with the eye may differ from one without it in - and, with
	 * {@link #oldEyeIcon}, from the pictures of parts E and G, which drew it in the total's row.
	 */
	static java.awt.Rectangle eyeIcon(BankPriceMovementPanel panel)
	{
		final JLabel eye = findLabel(panel.captionRow(), l -> BankPriceMovementPanel.HIDE_AMOUNTS_TIP.equals(l.getToolTipText())
			|| BankPriceMovementPanel.SHOW_AMOUNTS_TIP.equals(l.getToolTipText()));
		if (eye == null)
		{
			throw new AssertionError("no eye in the card's top row");
		}
		final java.awt.Insets in = eye.getInsets();
		return SwingUtilities.convertRectangle(eye.getParent(), new java.awt.Rectangle(eye.getX() + in.left,
			eye.getY() + in.top, eye.getIcon().getIconWidth(), eye.getIcon().getIconHeight()), panel);
	}

	/**
	 * EDT, on a panel already laid out: the 12 x 12 box the eye's icon was painted in BEFORE 1.1.0 part I moved it, in
	 * {@code panel}'s own coordinates - in the total's row, ending where the Refresh link's box begins (the eye's label had no
	 * right inset), centred in the link's height the way the holder that carried both centred the eye's 16 px label (12 px of
	 * icon and 2 px above and below) in the link's cell. The pictures pinned for parts E and G, kept on disk, show the eye
	 * there, and nothing is drawn there now: it is the second rectangle a part I picture may differ from them in.
	 */
	static java.awt.Rectangle oldEyeIcon(BankPriceMovementPanel panel)
	{
		final java.awt.Rectangle link = SwingUtilities.convertRectangle(panel.refreshLabel().getParent(),
			panel.refreshLabel().getBounds(), panel);
		final int label = EyeIcon.SIZE + 4;
		return new java.awt.Rectangle(link.x - EyeIcon.SIZE, link.y + (link.height - label) / 2 + 2, EyeIcon.SIZE,
			EyeIcon.SIZE);
	}

	/**
	 * EDT, on a panel already laid out: the 12 x 12 box the List options icon (the two gears at the right end of the search row)
	 * is painted in, in {@code panel}'s own coordinates (1.1.0 part G): at the label's left inset (the 6 px of air on its left) and
	 * centred in the label's height, which the search row stretches to the box's. The one rectangle, with the shortened box beside
	 * it, a picture with the gears may differ in from one without.
	 */
	static java.awt.Rectangle listOptionsIcon(BankPriceMovementPanel panel)
	{
		final JLabel gears = listOptionsLabel(panel);
		final java.awt.Insets in = gears.getInsets();
		final int h = gears.getIcon().getIconHeight();
		return SwingUtilities.convertRectangle(gears.getParent(), new java.awt.Rectangle(gears.getX() + in.left,
			gears.getY() + in.top + (gears.getHeight() - in.top - in.bottom - h) / 2, gears.getIcon().getIconWidth(), h),
			panel);
	}

	/** EDT: the List options label of {@code panel}'s search row (1.1.0 part G); fails when the row has none. */
	static JLabel listOptionsLabel(BankPriceMovementPanel panel)
	{
		final JLabel gears = findLabel(panel.searchField().getParent(),
			l -> BankPriceMovementPanel.LIST_OPTIONS_TIP.equals(l.getToolTipText()));
		if (gears == null)
		{
			throw new AssertionError("no List options icon in the search row");
		}
		return gears;
	}

	/** The first label of {@code root}'s tree that {@code wanted} accepts, or null. */
	@Nullable
	private static JLabel findLabel(Component root, java.util.function.Predicate<JLabel> wanted)
	{
		if (root instanceof JLabel && wanted.test((JLabel) root))
		{
			return (JLabel) root;
		}
		if (root instanceof Container)
		{
			for (Component child : ((Container) root).getComponents())
			{
				final JLabel found = findLabel(child, wanted);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** The first component of {@code type} in {@code root}'s tree, or null. */
	@Nullable
	static <T> T find(Component root, Class<T> type)
	{
		if (type.isInstance(root))
		{
			return type.cast(root);
		}
		if (root instanceof Container)
		{
			for (Component child : ((Container) root).getComponents())
			{
				final T found = find(child, type);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** The whole sidebar in History over {@code series}, painted at {@link #historyHeight}; built and painted on the EDT. */
	public static BufferedImage renderHistory(HeroVisibility shown, BankHistorySeries series) throws Exception
	{
		final AtomicReference<BufferedImage> out = new AtomicReference<>();
		onEdt(() ->
		{
			final BankPriceMovementPanel panel = buildHistory(shown, series);
			out.set(paint(panel, historyHeight(panel)));
		});
		return out.get();
	}

	/** {@link #renderHistory} written as {@code <dir>/<name>} (the directory is created if it is missing). */
	public static File writeHistory(File dir, String name, HeroVisibility shown, BankHistorySeries series)
		throws Exception
	{
		final BufferedImage image = renderHistory(shown, series);
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
	 * An {@link MovementRowPanel#ICON_WIDTH} x {@link MovementRowPanel#ICON_HEIGHT} stand-in for the item sprite: a rounded block in a colour derived from the id with a lighter
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
			// Centred on x = 15 of the 36 px frame, not on its middle, because that is where the client puts real
			// item art (addendum AR measured it on the user's screenshot) - a stand-in drawn dead centre would make
			// every render show the pictures ART_NUDGE px right of where they really stand.
			g.fillRoundRect(2, 3, 27, 26, 9, 9);
			g.setColor(Color.getHSBColor(hue, 0.35f, 0.85f));
			g.fillOval(8, 9, 15, 14);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	/**
	 * The published rows for a set of view switches (Q5, R5, AV): the twelve and the Crystal body at what its seeds
	 * are worth - with EVERY switch since addendum AV, because an untradeable made from tradeable parts always
	 * counts - and with {@code countUntradeables} on the two alch stacks after them.
	 *
	 * <p>Each lands where the LIT ordering puts it, because that is the list the service would publish. "Biggest
	 * gainers" is percent descending with the rows that have no baseline last, so the two alch stacks - which can
	 * never have one - go at the end, and the Crystal body, which has a real baseline and a real move (R3), goes
	 * among the movers: its -8.7% is under Zulrah's scales and over the row with no baseline at all, which puts
	 * it two rows above the pair and makes the picture the comparison R5 asks for.
	 */
	static List<MovementRow> rows(ViewOptions options)
	{
		if (options != null && options.livePrices())
		{
			return liveRows();
		}
		final List<MovementRow> out = withCrystalBody(rows());
		if (options == null || !options.countUntradeables())
		{
			return Collections.unmodifiableList(out);
		}
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
		// AV: the Crystal body is in this list too - the live switch is on and the untradeables switch off - just
		// above the row with no baseline, which leaves the four rows re-priced below where they were.
		final List<MovementRow> out = withCrystalBody(rows());
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
	 * {@code rows} with the Crystal body where "Percent change", biggest first, puts it (R5): its -8.7% is under
	 * Zulrah's scales and over the row with no baseline, so it goes directly before the last row. A fresh list.
	 */
	private static List<MovementRow> withCrystalBody(List<MovementRow> rows)
	{
		final List<MovementRow> out = new ArrayList<>(rows);
		out.add(out.size() - 1, crystalBody());
		return out;
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
	 * (-8.77 %, printed -8.7% because a percentage truncates toward zero) and paints exactly as a guide row does,
	 * rail and all.
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
	 * The whole-bank figures for a set of view switches (Q5, R3, AV): the probe's with the Crystal body added -
	 * with every switch since addendum AV - and with {@code countUntradeables} on the two alch stacks added to the
	 * VALUE and to {@code itemsTotal} as well.
	 *
	 * <p>The two alch stacks stop there - they have no guide price, so they are not {@code itemsPriced}, and no
	 * baseline, so no window covers them. The Crystal body is a priced stack with a baseline like any other
	 * (R3): it counts in {@code itemsPriced} as well, and in every window's covered set - and so its own move is
	 * in every window's move, both ends of it, exactly as the service sums a covered stack (the fixture has one
	 * "then" for the body, {@link #crystalBody()}'s, and uses it for every window).
	 */
	static PortfolioSummary summary(ViewOptions options)
	{
		// AV: the Crystal body counts whatever the untradeables switch says - a priced stack with a baseline (R3), so
		// it is in itemsPriced, in every window's covered set and in every window's move.
		final PortfolioSummary base = summary();
		final long bodyThen = CRYSTAL_SEEDS * CRYSTAL_SEED_THEN;
		final Map<MovementWindow, WindowMove> moves = new EnumMap<>(MovementWindow.class);
		for (WindowMove m : base.moves().values())
		{
			final long valueThen = m.valueThen() + bodyThen;
			final long deltaGp = m.deltaGp() + (CRYSTAL_BODY_NOW - bodyThen);
			moves.put(m.window(), new WindowMove(m.window(), m.thenDay(), valueThen,
				m.valueNowCovered() + CRYSTAL_BODY_NOW, deltaGp, deltaGp * 100.0 / valueThen, m.itemsCovered() + 1));
		}
		final PortfolioSummary parts = new PortfolioSummary(base.valueStacks() + CRYSTAL_BODY_NOW,
			base.itemsPriced() + 1, base.itemsTotal() + 1, moves);
		if (options != null && options.livePrices())
		{
			// T5: the same whole-bank figures, with the live count the card's tooltip printed until addendum AF.
			return new PortfolioSummary(parts.valueStacks(), parts.itemsPriced(), parts.itemsTotal(), parts.moves(),
				parts.currencyGp(), LIVE_STACKS);
		}
		if (options == null || !options.countUntradeables())
		{
			return parts;
		}
		return new PortfolioSummary(parts.valueStacks() + GRACEFUL_HOOD_ALCH + AVERNIC_DEFENDER_ALCH,
			parts.itemsPriced(), parts.itemsTotal() + 2, parts.moves());
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
