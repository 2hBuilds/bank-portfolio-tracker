package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The developer-mode command bridge (contract C40): ONE command string in, ONE JSON string out, so the
 * Effect Lab's HTTP server - and through it a script, or Claude - can drive this sidebar with no person at
 * the keyboard. {@link BpmDevBridge} holds the only instance, and only while a developer-mode client has the
 * plugin on; {@code GET /bpm?cmd=...} on the lab's port is the way in. Shaped after
 * {@code com.lootandbeam.ui.DevCommands}, which does the same job for Loot and Beam.
 *
 * <p><b>Threading.</b> {@link #apply(String)} is called from the lab's HTTP thread. Everything it touches -
 * the panel, its widgets - is Swing state, so the work runs on the EDT: inline when the caller is already
 * there, otherwise through {@code invokeLater} awaited for {@link #EDT_TIMEOUT_MS}. A timeout is an answer
 * ({@code {"ok":false,...}}), never a wedged HTTP thread; nothing here throws.
 *
 * <p><b>Commands</b> (one per call; {@code key} or {@code key=value}):
 * <pre>
 * state                      the whole picture: filter, status, hero, row counts, the first ten rows
 * window=1d|7d|30d|90d|180d  which guide-table revision the movement is measured against (K10)
 * sort=percent|gp|price|stack  which of the four columns the list is ordered on (the lit one again = flip, W1)
 * dir=asc|desc               which way
 * min=&lt;gp&gt; / max=&lt;gp&gt;        the unit-price band ("100k", "1.5m", "1,000"; empty or 0 = no bound, and
 *                            a zero leaves the field BLANK on its placeholder, never a literal "0" - P3)
 * presets=&lt;a&gt;,&lt;b&gt;,&lt;c&gt;        the fold's three quick bands, in shorthand and any order
 *                            (presets=default puts 100k / 1m / 10m back - Z4)
 * fold=on|off|toggle         the price fold under the control row - the band button's own click (AA2)
 * hero=value|gp|pct|all|none which of the hero card's three figures are drawn (O5); a field word TOGGLES
 * opt=cash|untradeables|holding|live|inventory|all|none  the gear's five view switches (Q7, T8, Y1); a field
 *                            word TOGGLES
 * refresh                    the Refresh button (30 s cooldown lives in the service)
 * more                       the "Show more" button: one more page of rows
 * bank=&lt;id&gt;:&lt;qty&gt;;...        DEV ONLY - a synthetic bank, so the panel can be driven with no bank open
 *                            (995 and 13204 are its CURRENCY worth, never a row - the same rule as the reader)
 * wiki=on|off                turn the GUIDE-HISTORY client off, to exercise "Wiki history down"
 * shot[=&lt;name&gt;]              a PNG of the whole sidebar in ~/.runelite/bank-portfolio-tracker/shots/ (the sidebar must be OPEN)
 * </pre>
 *
 * <p>Every command answers with the {@code state} object ({@code shot} puts its {@code path}, {@code width}
 * and {@code height} in front of it), so a script never needs a second call to see what it did.
 *
 * <p>Since addendum L, {@code state.status} carries the whole of how the baseline was CHOSEN (L13):
 * {@code anchorDay} and the {@code agree} fraction (over {@code agreeSamples} items) that derived it,
 * {@code r0Day} / {@code r0RevId}, and for the current window {@code thenDay} / {@code thenRevId}, plus
 * {@code degraded} with its {@code degradedReason}, {@code mappingAtMillis} and {@code indexAtMillis}; and
 * {@code state.windows} echoes EVERY window's {@code thenDay} / {@code thenRevId} (L13: "per window"). Those are
 * how a live session proves the baseline is the DAY it should be rather than merely a plausible-looking number:
 * addendum K's "newest revision at or before now minus the window" landed on the intended Jagex day only about
 * half the time (L-C), and these fields are what make that visible from a terminal. Since addendum M
 * {@code state.portfolio} echoes the bank value line (M5): {@code valueNow} over the whole bank and one
 * {@code {thenDay, valueThen, valueNowCovered, deltaGp, deltaPct, itemsCovered}} per window with a baseline;
 * since addendum P it also carries {@code currencyGp}, the coins and platinum tokens that are already counted
 * INSIDE {@code valueNow} (P1).
 *
 * <p><b>The filter verbs press the widgets.</b> {@code window/sort/dir/min/max/refresh} call the panel's own
 * {@code selectWindow / clickSort / setDescending / applyMin / applyMax / refreshNow}, which are the very
 * methods the buttons and the fields call, so the config write ({@code prefs.save}), the recompute
 * ({@code service.setFilter}) and the repaint all happen exactly as they do under a mouse. {@code sort=} on
 * the sort that is already active therefore FLIPS the direction, which is what a second click does.
 *
 * <p><b>Addendum O replaced the look verb with the hero verb.</b> The user picked the Ticker design, so there
 * is one look and no {@code look=} - the verb answers "unknown command" - and in its place {@code hero=} drives
 * addendum O's three show/hide switches on the bank value card (O5): {@code value}, {@code gp} and {@code pct}
 * each TOGGLE their figure, {@code all} and {@code none} set all three, and every answer echoes
 * {@code state.hero} as {@code {value, gp, pct}} so {@code hero=none; shot=hero-none} needs no second call to
 * prove what was photographed.
 *
 * <p><b>Addendum Q adds the gear menu's switches as {@code opt=}</b> (Q7), shaped on {@code hero=} down to the
 * last detail: {@code cash}, {@code untradeables} and {@code holding} each TOGGLE their switch against what the
 * sidebar is using now, {@code all} turns them all on and {@code none} turns them all off, and every answer
 * echoes {@code state.options}. There is no {@code gear} verb: the menu
 * is a Swing popup with nothing behind it that a script cannot reach through these switches, and
 * {@code shot=} photographs the gear itself.
 *
 * <p><b>Addendum T adds the fourth switch to that family</b> (T8): {@code opt=live} toggles the live traded
 * prices, {@code state.options} echoes it as the fourth key {@code live}, and {@code state.live} carries what the
 * traded feeds have actually delivered - {@code {fetchedAt, latestItems, liveRows, guideRows, alchRows}} - so one
 * call proves both that the switch moved and what it did to the list. It is in the {@code opt=} family rather than
 * a verb of its own because it is one field of the same {@link ViewOptions} and goes through the same widget,
 * {@link BankPriceMovementPanel#setOptions}; {@code state.rows[].source} then reads {@code LIVE} for a row the
 * liquidity checks passed - three of them under addendum T, five since addendum V added the two that read
 * YESTERDAY's daily bucket.
 *
 * <p><b>Addendum U puts the two DAYS in that same object</b> (U4): {@code state.live} gains {@code liveDay},
 * the UTC calendar date of the {@code /latest} snapshot the live prices stand on, and {@code windowDays},
 * the traded day each window is actually measured against ({@code {"1d": "2026-09-11", ...}}). They are here
 * because the live series keeps its OWN calendar: addendum T counted a live window back from the GUIDE's anchor
 * day, which is a day behind the clock for as long as Jagex has not published today's table, so on live look 5
 * "1d" reached back two days and Partyhat set read +30 % where yesterday's traded average gives about +11 %.
 * {@code state.windows} and {@code state.status} still carry the guide's days, untouched, and the pair of
 * answers being DIFFERENT on such a day is the fix rather than a fault - which is why both are printed and
 * neither is derived from the other.
 *
 * <p><b>Addendum V adds no verb at all</b>, and is worth a line here only because {@code opt=holding} changes
 * what one existing answer means: with the switch on, the gp column compares the STACK's change rather than one
 * item's (Q6), so {@code opt=holding; sort=gp} really does reorder the list. Nothing else follows the switch -
 * addendum V's V1, which had made the price column follow it too, was reverted by addendum W.
 *
 * <p><b>Addendum W leaves ONE sort vocabulary</b> (W4), which is the one this bridge was born with.
 * {@code sort=} is {@link SortMode#parse(String)} and nothing else: the four columns of W1 - {@code percent},
 * {@code gp}, {@code price} and the new {@code stack} - each of them also answering to its label and to every
 * alias older scripts were written against ("pct", "% move", "amount", "unit"), and naming the column that is
 * already lit FLIPS the direction, because that is what pressing it again does. Addendum N's six named
 * orderings and their {@code order=} verb are DELETED with the {@code SortOrder} enum that carried them:
 * {@code order=} still has a case here, but only so it can say where the words went rather than answer the
 * bare "unknown command" a script would have to guess at. {@code state.filter} loses its {@code order}
 * sentence for the same reason and keeps the pair the sidebar actually holds, {@code sort} (now "Percent
 * change" / "gp change" / "Item price" / "Stack price") and {@code descending}; V2's {@code sortHint} leaves
 * {@code state.panel} on the panel's own side of the seam, this class having never had anything to add to it.
 *
 * <p><b>Addendum Y adds the fifth switch to the {@code opt=} family</b> (Y1): {@code opt=inventory} - also
 * {@code inv}, {@code gear}, {@code worn} - toggles whether what the player is carrying and wearing counts in
 * the bank value and is listed with the bank's stacks, {@code all} and {@code none} now cover five, and
 * {@code state.options} echoes it as the fifth key {@code inventory}. While it is ON, every row in
 * {@code state.rows[]} carries {@code bankQty} / {@code invQty} / {@code wornQty} beside {@code qty}, which
 * they sum to - the split the row's own tooltip prints as "3 in bank, 1 in inventory, 1 worn" (Y3) - and while
 * it is off the three keys are absent, because there is nothing split to say. The switch itself changes no
 * fetch: the two containers are read from the client, on the client thread, when a bank event arrives or when
 * {@code refresh} is sent (Y2), so {@code opt=inventory} followed by {@code refresh} is how a script makes the
 * carried half move without opening a bank.
 *
 * <p><b>Addendum Z adds {@code presets=}</b> (Z4), the fold's three quick bands: {@code presets=1m,10m,100m} in any
 * order and any shorthand {@link MovementMath#parseGp(String)} reads, {@code presets=default} to put 100k / 1m / 10m
 * back, and anything that is not three distinct positive amounts refused with the sidebar left exactly as it was.
 * It presses {@link BankPriceMovementPanel#setPresets}, which is the path the three boxes at the foot of the gear
 * menu take - applied to the chips AND written through the {@code Prefs} seam - so a {@code /bpm} session leaves the
 * same stored line behind as a hand session. Every answer carries {@code state.presets} as the three gp amounts
 * ({@code [100000, 1000000, 10000000]}) and {@code state.panel.presetLabels} as the four chips the fold draws
 * ({@code ["All", "100k+", "1m+", "10m+"]}), so one call proves both what was stored and what the user would read.
 * The band itself is untouched by any of it: a preset says what a chip OFFERS, and {@code min=} / {@code max=} are
 * still the only verbs that move {@code gpMin} / {@code gpMax}.
 *
 * <p><b>Addendum AA adds {@code fold=}</b> (AA2), the control those chips live in: {@code on} opens the price fold,
 * {@code off} closes it and {@code toggle} flips it, each of them by pressing the band button's own road -
 * {@link BankPriceMovementPanel#pressFold(boolean)} for the two states, {@link BankPriceMovementPanel#toggleFold()}
 * for the gesture - which is the only road that REMEMBERS the choice (the fold is the fifteenth stored key since
 * AA1). The two states are idempotent, the panel writing nothing when nothing changed, so a script may send the same
 * word as often as it likes. {@code state.panel.foldOpen} is where the answer reads back; it has been in
 * {@code describe()} since the fold existed, so this verb adds no echo of its own. It is not the band: folding the
 * controls away moves no row and writes no bound.
 */
public class BpmCommands implements Function<String, String>
{
	/** How long a command may take on the Swing thread before the HTTP caller is answered anyway. */
	static final long EDT_TIMEOUT_MS = 5_000;
	/** The lab's own screenshot directory, so a panel shot lands beside the world shots it is compared with. */
	private static final String SHOT_DIR = "bank-portfolio-tracker/shots";
	/** The one command that needs a window: see {@link #shot}. The tests assert this text, not the constant. */
	static final String NOT_SHOWING =
		"panel not showing: open Bank Portfolio Tracker in the sidebar (an off-screen print comes out blank)";
	private static final String NO_ACCOUNT =
		"no account was wired into this bridge, so a synthetic bank would be saved under account 0";
	/**
	 * The other half of the same rule: an {@link Account} that answers 0 (nobody logged in) or -1 (the client
	 * has not logged in yet, {@code OAuthApi.getAccountHash}) is no more an account than a missing seam. The
	 * tests assert this text, not the constant.
	 */
	static final String NOT_LOGGED_IN =
		"nobody is logged in, so a synthetic bank would be saved under account 0 - log in first, then bank=";
	/** How many rows {@code state} carries, so a bank of 800 does not come back down the HTTP pipe. */
	static final int STATE_ROWS = 10;
	/** The seven words {@code opt=} takes, for a refusal that says what to type instead (Q7, T8, Y1). */
	static final String OPTION_VERBS = "cash, untradeables, holding, live, inventory, all or none";
	/**
	 * The four columns {@code sort=} presses, as the shortest word for each (W1), for a refusal that says what to
	 * type instead. Every label and every older alias is accepted too - {@link SortMode#parse(String)} is the whole
	 * vocabulary - but a refusal that listed all fifteen spellings would be read by nobody. The tests assert this
	 * text, not the constant.
	 */
	static final String SORT_VERBS = "percent, gp, price, stack";
	/**
	 * What {@code order=} answers since addendum W (W4). The six named orderings of addendum N went with the
	 * {@link SortMode} column model coming back, so the verb is kept alive purely to say so: a script written
	 * against the N or V build sends {@code order=valuable}, and "unknown command 'order'" would leave its author
	 * guessing at a vocabulary that no longer exists, where this hands them the two verbs that replace it. The
	 * tests assert this text, not the constant.
	 */
	static final String ORDER_GONE =
		"order= is gone since addendum W - use sort=<column> (percent, gp, price, stack) and dir=asc|desc";
	/**
	 * What {@code presets=} answers when the text is not three distinct positive gp amounts (Z4), up to the value
	 * itself: the rule ("three distinct") and an example of a line that obeys it, because the verb takes free text
	 * and the three ways to get it wrong - two amounts, a duplicate, a word that is not a number - all land here.
	 * The refusal leaves the chips exactly as they were, which is the boxes' own behaviour (Z2). The tests assert
	 * this text with the offending value appended, not the constant.
	 */
	static final String PRESETS_REFUSAL = "presets= wants three distinct gp values (100k, 1m, 10m), not '";
	/**
	 * The three words {@code fold=} takes (AA2), for a refusal that says what to type instead. Two of them are
	 * states and the third is a gesture, which is the whole vocabulary the control has: the band button toggles,
	 * and a script that knows which way it wants the fold says so rather than reading first.
	 */
	static final String FOLD_VERBS = "on, off or toggle";
	private static final Logger log = LoggerFactory.getLogger(BpmCommands.class);

	/**
	 * Where a synthetic {@code bank=} gets its identity. The plugin caches the live pair on the client thread
	 * and hands it over here, and the reason is {@code PriceService.setLoggedIn}: on the next {@code LOGGED_IN}
	 * it keeps the snapshot it holds only when {@code belongsTo(bank, accountHash, profile)} - the snapshot's
	 * own stamps against the live ones - and otherwise loads the stored bank over it. Synthetic rows stamped
	 * with zeros would therefore be swept away by the next login or world hop, mid-experiment.
	 *
	 * <p>(Corrected 2026-09-09. This javadoc used to say the pair was carried so the file would not be written
	 * as {@code bank-0-STANDARD.json} "and then loaded over the real one on the next login". The file name half
	 * is right - {@link PriceStore#saveBank} keys the file from the snapshot's own fields - but a
	 * {@code bank-0} file is never LOADED by anything: {@code loadBank} is always called with the live hash. A
	 * synthetic bank is written over the live account's own file instead, which is why {@link #bank} refuses
	 * unless there is a real account to own it, and why a live session should re-open a bank afterwards.)
	 */
	public interface Account
	{
		/** The logged-in account hash, or 0 when nobody is logged in. */
		long hash();

		/** {@code RuneScapeProfileType.getCurrent(client).name()}, never null. */
		String profileType();
	}

	private final BankPriceMovementPanel panel;
	private final PriceService service;
	private final Gson gson;
	@Nullable
	private final Account account;
	private final File shotDir;
	/**
	 * Whether the sidebar panel is on screen, i.e. whether there is anything to photograph. The seam exists so
	 * a test can print a panel that no window ever realised; in the client it is always {@code panel::isShowing}.
	 */
	private final BooleanSupplier showing;
	/** True once {@code bank=} has pushed a made-up bank in, so {@code state} can say the rows are not real. */
	private boolean syntheticBank;

	/**
	 * The contract's constructor (C40): every verb works exactly as it does live except {@code bank=}, which
	 * is refused without an {@link Account} to stamp the synthetic snapshot with.
	 */
	public BpmCommands(BankPriceMovementPanel panel, PriceService service, Gson gson)
	{
		this(panel, service, gson, null);
	}

	/** What {@link BankPriceMovementPlugin#startUp()} builds: the contract's three plus the live account. */
	public BpmCommands(BankPriceMovementPanel panel, PriceService service, Gson gson, @Nullable Account account)
	{
		this(panel, service, gson, account, new File(RuneLite.RUNELITE_DIR, SHOT_DIR), panel::isShowing);
	}

	/**
	 * @param shotDir where {@code shot} writes; the tests point it at a temporary folder
	 * @param showing stands in for {@link java.awt.Component#isShowing()} on the panel, which is false for the
	 *                headless panel the tests build; they pass a supplier that says otherwise
	 */
	BpmCommands(BankPriceMovementPanel panel, PriceService service, Gson gson, @Nullable Account account,
		File shotDir, BooleanSupplier showing)
	{
		this.panel = panel;
		this.service = service;
		this.gson = gson;
		this.account = account;
		this.shotDir = shotDir;
		this.showing = showing;
	}

	// ---------------------------------------------------------------- the hop

	@Override
	public String apply(String cmd)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			return run(cmd);
		}
		final CompletableFuture<String> f = new CompletableFuture<>();
		SwingUtilities.invokeLater(() -> f.complete(run(cmd)));
		final long timeout = edtTimeoutMs();
		try
		{
			return f.get(timeout, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e)
		{
			return json(error("timed out: the Swing thread did not answer within " + timeout
				+ " ms (a modal dialog open in the client?)"));
		}
		catch (InterruptedException ignored)
		{
			// The bridge thread is the lab's own request thread: answering is the whole of its job, so the
			// interrupt is consumed here rather than re-asserted (the Hub's reviewers flag the re-assert idiom).
			return json(error("interrupted while waiting for the Swing thread"));
		}
		catch (ExecutionException e)
		{
			return json(error(String.valueOf(e.getCause())));
		}
	}

	/**
	 * How long {@link #apply(String)} waits for the Swing thread. A seam, not a setting: the test that proves
	 * a wedged EDT still gets an answer overrides it so it does not have to wait five seconds for the proof.
	 */
	long edtTimeoutMs()
	{
		return EDT_TIMEOUT_MS;
	}

	/** EDT. One command; never throws - a failure comes back as {@code {"ok":false,"error":...}}. */
	private String run(String cmd)
	{
		try
		{
			return json(dispatch(cmd));
		}
		catch (Throwable t)
		{
			log.warn("dev command failed: {}", cmd, t);
			return json(error(String.valueOf(t)));
		}
	}

	// ---------------------------------------------------------------- parsing and dispatch

	private Map<String, Object> dispatch(@Nullable String cmd)
	{
		final String s = cmd == null ? "" : cmd.trim();
		if (s.isEmpty())
		{
			return error("empty command (try 'state')");
		}
		// The FIRST '=' splits, so a value may contain one; a '+' in a URL query decodes to a space, hence trim.
		final int eq = s.indexOf('=');
		final String key = (eq < 0 ? s : s.substring(0, eq)).trim().toLowerCase(Locale.ROOT);
		final String value = eq < 0 ? null : s.substring(eq + 1).trim();

		switch (key)
		{
			case "state":
				return state();
			case "window":
				return window(value);
			case "sort":
				return sort(value);
			case "order":
				return order();
			case "hero":
				return hero(value);
			case "opt":
			case "options":
				return opt(value);
			case "dir":
				return dir(value);
			case "min":
				return band(value, true);
			case "max":
				return band(value, false);
			case "presets":
			case "preset":
				return presets(value);
			case "fold":
				return fold(value);
			case "refresh":
				return refresh();
			case "more":
				return more();
			case "bank":
				return bank(value);
			case "wiki":
				return wiki(value);
			case "shot":
				return shot(value);
			default:
				return error("unknown command '" + key + "' (state, window=, sort=, dir=, min=, max=, presets=,"
					+ " fold=, hero=, opt=, refresh, more, bank=, wiki=, shot[=])");
		}
	}

	// ---------------------------------------------------------------- the filter verbs

	/**
	 * A window chip (K10). {@link MovementWindow#parse(String)} also takes the legacy trade-era spellings
	 * ("1h", "24h") and folds them onto {@link MovementWindow#D1}, so a script written against the first build
	 * keeps working rather than answering {@code ok:false} on a name that no longer exists.
	 */
	private Map<String, Object> window(@Nullable String value)
	{
		final MovementWindow w = MovementWindow.parse(value);
		if (w == null)
		{
			return error("window= wants 1d, 7d, 30d, 90d or 180d, not '" + value + "'");
		}
		panel.selectWindow(w);
		return state();
	}

	/**
	 * The sort control: ONE of the four columns of addendum W (W1, W4), pressed exactly as a click on the
	 * sidebar's sort button presses it.
	 *
	 * <p>The word is anything {@link SortMode#parse(String)} knows - the label ("Stack price"), the constant
	 * ("STACK_VALUE"), the short verb ("stack") or any alias an older script was written against ("pct",
	 * "% move", "amount", "unit") - and it names a COLUMN and nothing more. Naming the column that is already
	 * lit therefore flips the direction, because that is what pressing it again does (C29, W2): the live
	 * acceptance list sends {@code sort=stack} twice to watch the arrow turn over, and it has meant that since
	 * the first build.
	 *
	 * <p>There is no second vocabulary any more. Addendum N's six named orderings, which named a column AND a
	 * direction in one word, are gone with the {@code SortOrder} enum (W3), so the only word that NAMES a
	 * direction is {@code dir=} - the other way to move one is the lit column pressed again, above.
	 */
	private Map<String, Object> sort(@Nullable String value)
	{
		final SortMode m = SortMode.parse(value);
		if (m == null)
		{
			return error("sort= wants one of the four columns (" + SORT_VERBS + "), not '" + value
				+ "' (the direction is dir=asc|desc, or the lit column again)");
		}
		panel.clickSort(m);
		return state();
	}

	/**
	 * The gravestone of addendum N's {@code order=} (W4). The six named orderings it drove are deleted, and this
	 * verb answers one sentence saying so and naming the two that replace it - {@code sort=} for the column,
	 * {@code dir=} for the direction.
	 *
	 * <p>It is a case in the switch rather than a deletion because scripts outlive addenda: {@code order=} is in
	 * the N, O, Q, T, U and V live lists, in the lab's own {@code /help} line until this wave rewrote it, and in
	 * whatever the operator has in their shell history. "unknown command 'order'" would be true and useless -
	 * the vocabulary moved, and this is the one place that can say where to.
	 */
	private static Map<String, Object> order()
	{
		return error(ORDER_GONE);
	}

	/**
	 * Addendum O's {@code hero=} verb (O5): which of the bank value card's three figures are drawn. A FIELD
	 * word toggles one figure - {@code hero=value} flips the whole-bank total, {@code gp} the card's gp move,
	 * {@code pct} its percentage - while {@code all} shows every figure and {@code none} hides all three,
	 * leaving the card with its caption, its chips and its footnote alone. The vocabulary and the toggling are
	 * {@link HeroVisibility#applyVerb(String)}, so a test can walk the eight combinations without a panel.
	 *
	 * <p>It presses {@link BankPriceMovementPanel#setHeroVisibility}, the card's own right-click check items -
	 * not the bare {@code applyHeroVisibility} - for the reason every other verb here presses a widget: the
	 * promise of this bridge is that a {@code /bpm} session and a hand session leave the same stored settings
	 * behind. So the figures a script hides stay hidden in RuneLite's settings panel and after the next
	 * relaunch, exactly as if the user had ticked the boxes. Setting what the card is already showing is a
	 * no-op, so a script may send it as often as it likes.
	 *
	 * <p>The whole state comes back with {@code hero} in it, so {@code hero=none; shot=hero-none} proves what
	 * was photographed without a second call.
	 */
	private Map<String, Object> hero(@Nullable String value)
	{
		final HeroVisibility next = currentHero().applyVerb(value);
		if (next == null)
		{
			return error("hero= wants " + HeroVisibility.verbs() + ", not '" + value + "'");
		}
		panel.setHeroVisibility(next);
		return state();
	}

	/**
	 * What the card is showing; {@link HeroVisibility#ALL} before the panel has been asked (a mocked panel in
	 * the tests), which is also the default a fresh profile gets.
	 */
	private HeroVisibility currentHero()
	{
		final HeroVisibility hero = panel.heroVisibility();
		return hero == null ? HeroVisibility.ALL : hero;
	}

	/**
	 * Addendum Q's {@code opt=} verb (Q7), with addendum T's fourth switch in it (T8): the gear menu's view
	 * switches, driven exactly as {@code hero=} drives the card's. {@code cash} toggles whether coins and platinum
	 * tokens count in the bank value (Q4), {@code untradeables} whether untradeable stacks are listed at their
	 * High Alchemy value (Q5), {@code holding} whether a row prints the stack's worth instead of the unit price
	 * (Q6), {@code live} whether an actively traded item is priced from the wiki's traded series instead of the
	 * daily guide table (T1), {@code inventory} whether the player's inventory and worn gear count and are
	 * listed with the bank's stacks (Y1); {@code all} turns all five on and {@code none} turns all five off.
	 *
	 * <p>It presses {@link BankPriceMovementPanel#setOptions}, the gear menu's own check items - not the bare
	 * {@code applyOptions} - for the reason {@code hero=} presses {@code setHeroVisibility}: that method applies
	 * the switch AND writes it through the {@code Prefs} seam, which in the client is the plugin's config, and
	 * the plugin's own {@code saveOptions} is what hands the two figure-changing switches to
	 * {@link PriceService#setOptions}. So one press moves the sidebar, the sums and the stored settings together,
	 * and a script leaves the same profile behind as a hand session. Setting what the sidebar already has is a
	 * no-op, so a script may send it as often as it likes.
	 */
	private Map<String, Object> opt(@Nullable String value)
	{
		final ViewOptions next = applyOptionVerb(currentOptions(), value);
		if (next == null)
		{
			return error("opt= wants " + OPTION_VERBS + ", not '" + value + "'");
		}
		panel.setOptions(next);
		return state();
	}

	/**
	 * What the sidebar is using; {@link ViewOptions#DEFAULT} before the panel has been asked (a mocked panel in
	 * the tests), which is also what a fresh profile stores.
	 */
	private ViewOptions currentOptions()
	{
		final ViewOptions options = panel.options();
		return options == null ? ViewOptions.DEFAULT : options;
	}

	/**
	 * What the fold's chips offer; {@link BandPresets#DEFAULT} before the panel has been asked (a mocked panel in
	 * the tests), which is also what a fresh profile stores (Z1).
	 */
	private BandPresets currentPresets()
	{
		final BandPresets presets = panel.presets();
		return presets == null ? BandPresets.DEFAULT : presets;
	}

	/**
	 * Z4's {@code presets} echo: the three gp amounts, smallest first, as plain numbers
	 * ({@code [100000, 1000000, 10000000]}) - what a script compares, where the LABELS the user reads
	 * ("100k+") are {@code state.panel.presetLabels} and the panel's to spell.
	 *
	 * <p>A list rather than the value's own array, so the JSON is the same whatever Gson would make of a
	 * {@code long[]}, and so this method can never hand a caller something it could write through.
	 */
	private List<Long> presetsJson()
	{
		final long[] mins = currentPresets().mins();
		final List<Long> out = new ArrayList<>(mins.length);
		for (long min : mins)
		{
			out.add(min);
		}
		return out;
	}

	/**
	 * {@code opt=} as a pure function of the options it is applied to, the shape of
	 * {@link HeroVisibility#applyVerb(String)}: a field word TOGGLES its switch, {@code all} turns all five on
	 * and {@code none} turns all five off. The spellings are generous because the verb is typed by hand into a
	 * URL query ({@code coins}, {@code plat}, {@code untradables}, {@code alch}, {@code stack}, {@code traded},
	 * {@code inv}, {@code gear}, {@code worn}), and the text is trimmed and case-folded first.
	 *
	 * <p>It lives here rather than on {@link ViewOptions} because the value is shared with the panel and the
	 * service, which have no business knowing the developer bridge's vocabulary.
	 *
	 * @return the options to apply, or null when the text names none of the seven words of {@link #OPTION_VERBS} -
	 *         the caller then answers {@code ok:false} and leaves the sidebar exactly as it was
	 */
	@Nullable
	static ViewOptions applyOptionVerb(ViewOptions options, @Nullable String text)
	{
		if (text == null)
		{
			return null;
		}
		switch (text.trim().toLowerCase(Locale.ENGLISH))
		{
			case "cash":
			case "coins":
			case "coin":
			case "platinum":
			case "plat":
			case "countcash":
				return options.withCountCash(!options.countCash());
			case "untradeables":
			case "untradeable":
			case "untradables":
			case "untradable":
			case "alch":
			case "countuntradeables":
				return options.withCountUntradeables(!options.countUntradeables());
			case "holding":
			case "holdings":
			case "stack":
			case "stacks":
			case "holdingonrows":
				return options.withHoldingOnRows(!options.holdingOnRows());
			// T8. The fourth switch, with the spellings an operator reaches for when they are reading the
			// addendum ("live") or the study ("traded"); "liveprices" is the stored key, as the other three
			// accept theirs.
			case "live":
			case "liveprices":
			case "traded":
				return options.withLivePrices(!options.livePrices());
			// Y1. The fifth switch, with the four words the addendum and the gear menu put in an operator's
			// head ("inventory" and its short form, "gear" and "worn" from the label "Include inventory and
			// worn gear"); "countinventory" is the stored key, as the other four accept theirs.
			case "inventory":
			case "inv":
			case "gear":
			case "worn":
			case "countinventory":
				return options.withCountInventory(!options.countInventory());
			case "all":
			case "on":
				// All FIVE explicitly. The shorter constructors default livePrices and countInventory to on, so
				// "none" built through one of them would have left a switch standing - and "none" is the one word
				// whose whole promise is that nothing is left standing (T8, Y1).
				return new ViewOptions(true, true, true, true, true);
			case "none":
			case "off":
				return new ViewOptions(false, false, false, false, false);
			default:
				return null;
		}
	}

	private Map<String, Object> dir(@Nullable String value)
	{
		final String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		final boolean descending;
		if ("desc".equals(v) || "descending".equals(v) || "down".equals(v))
		{
			descending = true;
		}
		else if ("asc".equals(v) || "ascending".equals(v) || "up".equals(v))
		{
			descending = false;
		}
		else
		{
			return error("dir= wants asc or desc, not '" + value + "'");
		}
		panel.setDescending(descending);
		return state();
	}

	/**
	 * The Min / Max fields, typed into and applied exactly as Enter would. An empty value is "no bound";
	 * anything {@link MovementMath#parseGp(String)} refuses leaves the filter alone and marks the field red,
	 * and this answers {@code ok:false} with the field's own state visible in {@code state.panel.minInvalid}.
	 *
	 * <p><b>A zero is typed as nothing</b> (P3, live look 3 section 2), and the PANEL is what spells it: the
	 * operator's own text goes into the field untouched, and the field is re-rendered from the filter after a
	 * successful parse ({@code applyBound} through {@code renderBound}), which writes "" for a bound of nothing.
	 * So {@code max=0} leaves the placeholder "max gp" showing rather than a literal "0", and it does so by the
	 * same road a person typing a 0 and pressing Enter takes - the bridge no longer rewrites anything on the way
	 * in. Text the parser refuses reaches the field as typed, so a red field and an {@code ok:false} are
	 * unchanged.
	 */
	private Map<String, Object> band(@Nullable String value, boolean min)
	{
		final String v = value == null ? "" : value.trim();
		final boolean applied = min ? panel.applyMin(v) : panel.applyMax(v);
		if (!applied)
		{
			final Map<String, Object> m = error((min ? "min" : "max")
				+ "= wants a gp amount like 100k, 1.5m or 1,000, not '" + v
				+ "' (the field is red and the filter is unchanged)");
			m.putAll(stateBody());
			return m;
		}
		return state();
	}

	/**
	 * Addendum Z's {@code presets=} verb (Z4): the three quick bands the fold offers under the band button, set
	 * exactly as typing into the three boxes at the foot of the gear menu sets them.
	 *
	 * <p>The value is three gp amounts in any order and any shorthand {@link MovementMath#parseGp(String)} reads,
	 * separated by commas, slashes or spaces ({@code presets=1m,10m,100m}, {@code presets=100m 1m 10m}), and
	 * {@link BandPresets#parse(String)} sorts them; the word {@code default} - also {@code defaults} and
	 * {@code reset}, for the operator who reaches for the menu item's own wording - puts 100k / 1m / 10m back.
	 * Anything that is not three DISTINCT POSITIVE amounts is refused with the chips left exactly as they were,
	 * which is the boxes' own rule (Z2): a trio this bridge cannot read is a trio the sidebar would have painted
	 * red rather than saved.
	 *
	 * <p>It presses {@link BankPriceMovementPanel#setPresets}, not the bare {@code applyPresets}, for the reason
	 * {@code hero=} presses {@code setHeroVisibility} and {@code opt=} presses {@code setOptions}: that method
	 * applies the value AND writes it through the {@code Prefs} seam, which in the client is the plugin's config -
	 * so the line a script stores is the line RuneLite's settings page shows and the one the next launch opens the
	 * fold with. Setting the presets the sidebar already has is a no-op.
	 *
	 * <p>What it does NOT do is move the band: the chips are what the fold OFFERS, and {@code min=} / {@code max=}
	 * are still the only verbs that write {@code gpMin} / {@code gpMax}. So a script may re-cut the chips under a
	 * band the user has applied without disturbing the list, which is exactly what the live list asks for.
	 */
	private Map<String, Object> presets(@Nullable String value)
	{
		final String v = value == null ? "" : value.trim();
		final String word = v.toLowerCase(Locale.ROOT);
		final BandPresets next;
		if ("default".equals(word) || "defaults".equals(word) || "reset".equals(word))
		{
			next = BandPresets.DEFAULT;
		}
		else
		{
			next = BandPresets.parse(v);
		}
		if (next == null)
		{
			return error(PRESETS_REFUSAL + v + "'");
		}
		panel.setPresets(next);
		return state();
	}

	/**
	 * Addendum AA's {@code fold=} verb (AA2): the price fold under the control row - the chip strip the verb above
	 * fills, over the Min / Max fields - opened, closed or flipped.
	 *
	 * <p>It presses the band button's own road - {@link BankPriceMovementPanel#pressFold(boolean)} for the two
	 * states, {@link BankPriceMovementPanel#toggleFold()} for the gesture - for the reason every other verb here
	 * presses a widget: that road applies the change AND writes the fifteenth key through the {@code Prefs} seam
	 * (AA1), so a {@code /bpm} session leaves the same stored shape behind as a hand session and the next launch
	 * opens the way the script left it. The panel's other entry point, {@code setFoldOpen}, is the
	 * {@code ConfigChanged} road and deliberately remembers nothing - a script that used it would move the sidebar
	 * and forget by the relaunch.
	 *
	 * <p>{@code on} and {@code off} name a STATE and are therefore idempotent: the panel writes nothing when
	 * nothing changed, so a script may send the same word as often as it likes. That is the promise {@code hero=},
	 * {@code opt=} and {@code presets=} already make. {@code toggle} names the gesture and always flips.
	 *
	 * <p>The answer carries {@code state.panel.foldOpen}, which {@code describe()} has echoed since the fold
	 * existed, so this verb adds no echo of its own and one call still proves what was asked and what became of it.
	 */
	private Map<String, Object> fold(@Nullable String value)
	{
		// Quoted back as TYPED (band= and presets= do the same): an operator who sent "Open" is shown their own
		// word, and a bare "fold" is shown the nothing it named rather than the string "null".
		final String raw = value == null ? "" : value.trim();
		final String v = raw.toLowerCase(Locale.ROOT);
		if ("toggle".equals(v) || "flip".equals(v))
		{
			panel.toggleFold();
			return state();
		}
		final boolean open;
		if ("on".equals(v) || "open".equals(v) || "true".equals(v) || "1".equals(v))
		{
			open = true;
		}
		else if ("off".equals(v) || "close".equals(v) || "closed".equals(v) || "false".equals(v) || "0".equals(v))
		{
			open = false;
		}
		else
		{
			return error("fold= wants " + FOLD_VERBS + ", not '" + raw + "'");
		}
		panel.pressFold(open);
		return state();
	}

	// ---------------------------------------------------------------- the other verbs

	/** The Refresh button. The 30 s cooldown lives in the service and comes back in the status line. */
	private Map<String, Object> refresh()
	{
		panel.refreshNow();
		return state();
	}

	private Map<String, Object> more()
	{
		panel.showMore();
		return state();
	}

	/**
	 * A made-up bank, so the panel can be filled without walking to one: {@code bank=4151:1;995:1000}. Each
	 * row is named "item &lt;id&gt;" - {@link BankReader} is the only thing that knows real names, and it needs
	 * the client thread and a real container. The snapshot carries the LIVE account hash and profile type
	 * (see {@link Account}); {@code state.syntheticBank} says the rows are not real.
	 *
	 * <p><b>Coins and platinum tokens are a WORTH, not a row</b>, exactly as {@link BankReader} treats them
	 * (B097): {@code 995} and {@code 13204} feed {@link BankSnapshot#currencyGp} through
	 * {@link BankReader#currencyWorth} and make no row at all, so a staged bank cannot show a "item 995" line
	 * that no live bank can produce. {@code bank=995:200000000} is therefore a legal bank of nothing but cash -
	 * the case the bank value card has to survive - and only a request that names neither a row nor a coin is
	 * refused.
	 *
	 * <p><b>It is session-only</b> (checker, B021): the push goes through
	 * {@link PriceService#setBank(BankSnapshot, boolean)} with {@code persist} false, so the panel fills and the
	 * anchor day is re-derived but nothing is written. Before that the ordinary path saved it, and
	 * {@code PriceStore} keys the file from the snapshot's own fields - so two made-up rows atomically replaced
	 * the account's real 500-stack capture in {@code bank-<liveHash>-<profile>.json}, and the next launch drew
	 * them. The operator runs this verb while working the live acceptance list, so that was one keystroke away.
	 *
	 * <p><b>It refuses without a real account</b>, and that is a value test, not only a null test: the plugin
	 * zeroes its cached hash on {@code LOGIN_SCREEN} and {@code HOPPING} and does not restore it until the next
	 * login, so at the login screen the seam is present and answers 0. Pushing then would file the made-up rows
	 * as {@code bank-0-<profile>.json} - the file the {@link Account} seam exists to prevent, which no login
	 * ever loads and no sweep ever removes.
	 */
	private Map<String, Object> bank(@Nullable String value)
	{
		if (account == null)
		{
			return error(NO_ACCOUNT);
		}
		if (account.hash() <= 0L)
		{
			return error(NOT_LOGGED_IN);
		}
		final String v = value == null ? "" : value.trim();
		if (v.isEmpty())
		{
			return error("bank= wants <id>:<qty>[;<id>:<qty>...]");
		}
		final List<BankItem> items = new ArrayList<>();
		long currencyGp = 0L;
		for (String pair : v.split(";"))
		{
			final String p = pair.trim();
			if (p.isEmpty())
			{
				continue;
			}
			final int colon = p.indexOf(':');
			final String idText = (colon < 0 ? p : p.substring(0, colon)).trim();
			final String qtyText = colon < 0 ? "1" : p.substring(colon + 1).trim();
			final int id;
			final int qty;
			try
			{
				id = Integer.parseInt(idText);
				qty = Integer.parseInt(qtyText.isEmpty() ? "1" : qtyText);
			}
			catch (NumberFormatException e)
			{
				return error("bank= wants <id>:<qty>, not '" + p + "'");
			}
			if (id <= 0 || qty <= 0)
			{
				return error("bank= wants a positive id and quantity, not '" + p + "'");
			}
			// Currency the way BankReader hands it over (B097): a worth, not a row. A synthetic bank that could
			// hold "item 995" as a ROW would let the bridge stage a panel the live reader cannot produce - and
			// bank=995:200000000 is exactly how the bank value card's currency half gets exercised without one.
			final long currency = BankReader.currencyWorth(id, qty);
			if (currency > 0L)
			{
				currencyGp = PortfolioMath.clampedAdd(currencyGp, currency);
				continue;
			}
			items.add(new BankItem(id, qty, "item " + id, qty > 1));
		}
		if (items.isEmpty() && currencyGp == 0L)
		{
			return error("bank= wants <id>:<qty>[;<id>:<qty>...]");
		}
		final BankSnapshot snapshot = new BankSnapshot(items, System.currentTimeMillis(), account.hash(),
			account.profileType(), currencyGp);
		syntheticBank = true;
		// SESSION-ONLY (checker, B021). The live hash and profile are kept - setLoggedIn's belongsTo check
		// discards a snapshot that belongs to nobody - but the write is refused, so these made-up rows can no
		// longer replace the real capture in bank-<liveHash>-<profile>.json.
		service.setBank(snapshot, false);
		return state();
	}

	/**
	 * The history switch (C40, K10): {@link PriceService#setWikiEnabled(boolean)} passes straight through to
	 * {@link GuidePriceClient#setEnabled(boolean)}, which makes every fetch fail at once without a request.
	 * That is how the "Wiki history down - no movement" wording is exercised without unplugging the
	 * network - and note what it does and does not take away under addendum K: the unit prices are RuneLite's
	 * own guide table (K1) and keep coming, so the rows stay priced and lose only their movement.
	 *
	 * <p>The verb keeps the name {@code wiki=} it was born with: the wiki is still where the history comes
	 * from, and renaming it would break every script and every line of the lab docs for nothing.
	 */
	private Map<String, Object> wiki(@Nullable String value)
	{
		final String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		final boolean on;
		if ("on".equals(v) || "true".equals(v) || "1".equals(v))
		{
			on = true;
		}
		else if ("off".equals(v) || "false".equals(v) || "0".equals(v))
		{
			on = false;
		}
		else
		{
			return error("wiki= wants on or off, not '" + value + "'");
		}
		service.setWikiEnabled(on);
		return state();
	}

	/**
	 * The picture, and the one command that needs the sidebar to be OPEN. A {@code PluginPanel} that RuneLite
	 * has never put on screen has laid nothing out and painted nothing, and {@code printAll} on it draws the
	 * header over an empty content area - a picture of the right size and the wrong contents, which is worse
	 * than no picture at all (measured on the Loot and Beam panel, live session 2026-09-07: 225x880 with the
	 * sidebar shut against 242x1788 with it open). So an unshown panel is refused rather than photographed.
	 *
	 * <p><b>This is the one place in the plugin that writes a file on the EDT, deliberately</b> (noted by the
	 * checker 2026-09-09, so the exception is stated rather than merely true). {@link PriceStore} says a disk
	 * write may never land on the client thread or the EDT (PriceStore.java:59-63) and everything a user can
	 * reach obeys it; {@code shot} does not, because {@code printAll} REQUIRES the EDT and the whole verb -
	 * render, PNG encode, write - runs inside the hop {@link #apply(String)} makes. It is tolerated because it
	 * is developer-mode only, because it must answer the path of a file that is already on disk (a script's
	 * next line reads the PNG), and because the freeze is bounded twice over: a sidebar-sized image, and
	 * {@link #EDT_TIMEOUT_MS} on the caller. Nothing user-facing may copy this.
	 */
	private Map<String, Object> shot(@Nullable String name)
	{
		if (!showing.getAsBoolean())
		{
			return error(NOT_SHOWING);
		}
		try
		{
			final Shot shot = writeShot(panel.shotComponents(), shotDir, name);
			final Map<String, Object> m = ok();
			m.put("path", shot.file.getAbsolutePath());
			// The size is the proof the whole sidebar is in there: a shot no taller than the client window is
			// one that was cut at the viewport.
			m.put("width", shot.width);
			m.put("height", shot.height);
			// ...and the state behind the picture, so the PNG and the numbers it should show arrive together (C40).
			m.putAll(stateBody());
			return m;
		}
		catch (IOException e)
		{
			return error("could not write the shot: " + e);
		}
	}

	// ---------------------------------------------------------------- the state object

	/**
	 * EDT. The whole picture, and the body of every answer but {@code shot}'s.
	 *
	 * <p>The injected Gson does not write nulls, so a field with nothing behind it - {@code problem} with no
	 * trouble, {@code then} on a row with no baseline - is ABSENT rather than {@code null}. A script that
	 * indexes the map should use its "get or default" form; {@code jq .problem} answers {@code null} either way.
	 */
	private Map<String, Object> state()
	{
		final Map<String, Object> m = ok();
		m.putAll(stateBody());
		return m;
	}

	/** Everything {@code state} says, without the {@code ok} - so a refusal can carry it too. */
	private Map<String, Object> stateBody()
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("showing", showing.getAsBoolean());
		// O3: which of the card's three figures are on screen. The whole portfolio is still echoed below,
		// hidden figures included - this says what is PAINTED, never what is known.
		m.put("hero", heroJson(currentHero()));
		// Q4/Q7/Y1: the gear's five switches as the sidebar is using them, so a script that flips one can see
		// the new state and the figures it produced in the same answer. Unlike hero, these DO change the figures
		// below - portfolio.currencyGp reads 0 while cash is off, and an untradeable stack is a row only while
		// untradeables is on.
		m.put("options", currentOptions().asMap());
		m.put("filter", filterJson(service.filter()));
		// Z4: the three gp amounts the fold's chips offer, beside the band they would apply. They are a PANEL
		// value and not part of the filter above - nothing here is a bound, and changing them moves no row - so
		// they are echoed separately, and the four labels a reader actually sees are state.panel.presetLabels.
		m.put("presets", presetsJson());
		m.put("status", statusJson(service.currentStatus()));
		// T8: what the traded feeds actually delivered for the rows below - never what the switch above ASKED
		// for. The two can disagree for a whole tick (the switch goes on, the first /latest has not landed), and
		// this is the object that says so: all five figures are 0 until one has. U4 puts the live CALENDAR in it
		// too - liveDay and windowDays - which is a different calendar from the guide's anchor day in "status"
		// and "windows" below, and deliberately so (addendum U).
		m.put("live", liveJson(service.currentStatus()));
		m.put("windows", windowsJson());
		m.put("portfolio", portfolioJson(service.currentStatus()));
		m.put("shownRows", panel.shownRows());
		m.put("totalRows", panel.totalRows());
		// The panel's own one-liner: which card is up, the two fields' validity, the status text it drew.
		m.put("panel", parse(panel.describe()));
		if (syntheticBank)
		{
			// The rows below came from bank= and not from a real bank: nothing measured off them is evidence.
			m.put("syntheticBank", true);
		}
		// Y1: whether the three carried quantities are worth printing on each row below. Read once, from the
		// same object the "options" echo above was built from, so a row can never disagree with the switch
		// printed beside it.
		final boolean carried = currentOptions().countInventory();
		final List<MovementRow> rows = service.currentRows();
		final List<Map<String, Object>> out = new ArrayList<>();
		if (rows != null)
		{
			for (MovementRow row : rows)
			{
				if (out.size() >= STATE_ROWS)
				{
					break;
				}
				out.add(rowJson(row, carried));
			}
		}
		m.put("rows", out);
		return m;
	}

	/** The panel's JSON line as a tree, so it nests instead of being quoted; the raw text if it is not JSON. */
	private Object parse(@Nullable String text)
	{
		if (text == null)
		{
			return "";
		}
		try
		{
			final JsonElement e = gson.fromJson(text, JsonElement.class);
			return e == null ? text : e;
		}
		catch (RuntimeException e)
		{
			return text;
		}
	}

	/**
	 * O3's {@code hero} echo: the three show/hide switches as the card is drawing them right now
	 * ({@code {"value":true,"gp":false,"pct":true}}), so a script can assert what a shot should contain and,
	 * after a {@code hero=} toggle, see the new state in the same answer.
	 *
	 * <p>The shape is {@link HeroVisibility#asMap()} and nothing else - the same object the panel's own
	 * {@code describe()} echo is built from, so the two JSON answers about the card's switches can never drift
	 * apart on a name or an order.
	 */
	private static Map<String, Boolean> heroJson(HeroVisibility hero)
	{
		return hero.asMap();
	}

	/**
	 * T8's {@code live} echo: what addendum T's traded feeds delivered for the publish the rows below came from -
	 * {@code fetchedAt} (epoch millis of the {@code /latest} snapshot in use, 0 when there has been none),
	 * {@code latestItems} (how many items that snapshot names) and the three counts the bank's stacks split into,
	 * {@code liveRows} / {@code guideRows} / {@code alchRows}, over the WHOLE bank before the gp band.
	 *
	 * <p>It is the one-line check that the liquidity rules (T3, and addendum V's checks 4 and 5) and the
	 * painted list agree:
	 * {@code liveRows} must equal the number of {@code rows[]} whose {@code source} reads {@code LIVE} on an
	 * unfiltered bank of fewer than {@value #STATE_ROWS} stacks, and on a real one it is the figure the card's
	 * "N of M stacks live" line is drawn from.
	 *
	 * <p><b>Addendum U adds the two days the live series keeps its own calendar with</b> (U4), after the five
	 * figures and in that order: {@code liveDay}, the UTC date of that {@code /latest} snapshot - the day every
	 * live window counts back from - and {@code windowDays}, the traded day each window is ACTUALLY measured
	 * against ({@code {"1d": "2026-09-11", "7d": "2026-09-05", ...}}), which is {@code liveDay - N} unless the
	 * wiki had not closed that day yet and U2's one-day fallback took {@code liveDay - N - 1}. Both are ISO-8601
	 * and both are what was USED rather than what was wanted, which is the whole of the fix: addendum T counted a
	 * live window back from the GUIDE's anchor day, and on a day Jagex has not published yet that anchor is
	 * yesterday - so "1d" reached back two days and Partyhat set read +30 % against a two-day span (live look 5).
	 * The guide's own days are still {@code state.status.anchorDay} and {@code state.windows}, untouched by this
	 * wave: the two answers DIFFERING on such a day is the fix, not a fault.
	 *
	 * <p>Every figure is 0 while the switch is off ({@code PriceService.Status.LiveStatus#OFF}) and both days are
	 * null with it, so this object's answer with live prices off is exactly what the bridge said before addendum T
	 * existed - which is how a script proves the off position costs nothing rather than merely looking unchanged.
	 * A null day is ABSENT from the JSON rather than {@code null}, as every null field of this bridge is (the
	 * injected Gson does not write nulls); {@code jq} answers {@code null} either way, which is the reading the
	 * live list asks for.
	 *
	 * <p>The shape is {@code LiveStatus.asMap()} and nothing else, for the reason {@link #heroJson} gives: the
	 * panel's own {@code describe()} echo is built from the same method, so the two JSON answers about the traded
	 * feeds can never drift apart on a name or an order.
	 */
	private static Map<String, Object> liveJson(@Nullable PriceService.Status s)
	{
		final PriceService.Status.LiveStatus live = s == null || s.live() == null
			? PriceService.Status.LiveStatus.OFF : s.live();
		return live.asMap();
	}

	/**
	 * The filter as the service holds it. {@code sort} is the lit column's LABEL - "Percent change", "gp change",
	 * "Item price", "Stack price" since addendum W - and {@code descending} the arrow beside it; the two of them
	 * are the whole of the ordering (W1).
	 *
	 * <p>Addendum N's third field, {@code order}, which said the pair as one sentence ("Biggest gainers"), is gone
	 * with the {@link SortMode} column model coming back: there is no sentence to say any more, and a field that
	 * re-derived one here would be a name the sidebar does not print anywhere.
	 */
	private static Map<String, Object> filterJson(@Nullable RowFilter f)
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		if (f == null)
		{
			return m;
		}
		m.put("window", f.window().label());
		m.put("sort", f.sort().label());
		m.put("descending", f.descending());
		m.put("gpMin", f.gpMin());
		m.put("gpMax", f.gpMax());
		return m;
	}

	/**
	 * The header state, with addendum L's six selection fields at the end (L13) - the whole of how the baseline
	 * was chosen, so a live session can audit it instead of trusting it:
	 * <ul>
	 * <li>{@code anchorDay} - the derived day D the windows count back from (L3). NOT the wall clock: RuneLite's
	 * price table runs a Jagex day ahead of the wiki's newest revision for about half of every day (L-D).</li>
	 * <li>{@code agree} - the fraction of bank items whose RuneLite price equals the newest table, the
	 * measurement that derived D (>= 0.90 means "same day", L3).</li>
	 * <li>{@code r0Day} - the day the newest revision's own {@code %LAST_UPDATE%} claims, so {@code anchorDay ==
	 * r0Day} or exactly one day past it.</li>
	 * <li>{@code thenDay} - the day the CURRENT window's baseline table actually claims, which on a maintenance
	 * edit is not {@code anchorDay - window} (L5); this is the day the status line and every row tooltip print.</li>
	 * <li>{@code thenRevId} - the wiki revision that baseline was read from, so the same id can be fetched by
	 * hand and a row's "then" price read straight out of it.</li>
	 * <li>{@code degraded} - the rows run in a FALLBACK mode and deserve less trust: fewer than
	 * {@value PriceService#AGREE_MIN_SAMPLES} bank items could be compared with the newest table, so it stands in
	 * as "now" (L3), or the last history fetch failed and the rows run on stored baselines (L11);
	 * {@code degradedReason} says which. It is NOT "the baseline is not the day that was asked for" - a
	 * maintenance-edit retry that could not reach the target (L5) simply shows the real day in {@code thenDay},
	 * which a script compares against {@code anchorDay} minus the window (corrected by the checker, 2026-09-09).</li>
	 * <li>{@code agreeSamples}, {@code r0RevId}, {@code indexAtMillis} - the n behind {@code agree}, the newest
	 * revision's id, and when the revision index was fetched (L4's six-hour rule).</li>
	 * </ul>
	 * The dates are ISO-8601 ({@code "2026-09-07"}) and absent when unknown, so a script can compare them
	 * without reparsing the printed "07 Sep".
	 */
	private static Map<String, Object> statusJson(@Nullable PriceService.Status s)
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		if (s == null)
		{
			return m;
		}
		m.put("text", s.text());
		m.put("pricesAtMillis", s.pricesAtMillis());
		m.put("pricesAt", MovementMath.formatTime(s.pricesAtMillis()));
		m.put("bankAtMillis", s.bankAtMillis());
		m.put("bankAt", MovementMath.formatTime(s.bankAtMillis()));
		m.put("bankLoaded", s.bankLoaded());
		m.put("loggedIn", s.loggedIn());
		m.put("source", String.valueOf(s.source()));
		m.put("problem", s.problem());
		m.put("totalRows", s.totalRows());
		m.put("bankItems", s.bankItems());
		m.put("window", s.window().label());
		m.put("baselineLoaded", s.baselineLoaded());
		m.put("anchorDay", day(s.anchorDay()));
		m.put("agree", s.agree());
		m.put("agreeSamples", s.agreeSamples());
		m.put("r0Day", day(s.r0Day()));
		m.put("r0RevId", s.r0RevId());
		m.put("thenDay", day(s.thenDay()));
		m.put("thenRevId", s.thenRevId());
		m.put("degraded", s.degraded());
		m.put("degradedReason", s.degradedReason());
		m.put("mappingAtMillis", s.mappingAtMillis());
		m.put("indexAtMillis", s.indexAtMillis());
		return m;
	}

	/**
	 * L13's "per window {@code thenDay}/{@code revId}": every window's baseline day and revision as the service
	 * holds them ({@link PriceService#baseline}), not only the current window's, so one {@code state} shows all
	 * five choices and a live session can check a window's day without switching to it (added by the checker,
	 * 2026-09-09). A window with no baseline in memory is absent from the object; the day is the table's own
	 * {@code %LAST_UPDATE%} date (L7), ISO-8601, and {@code schema} is the shape of the document the map was read
	 * from ({@link PriceMapDto#schema}) - anything below {@link PriceMapDto#SCHEMA} is refused at start-up and so
	 * can never appear here (B028).
	 */
	private Map<String, Object> windowsJson()
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		for (MovementWindow w : MovementWindow.values())
		{
			final PriceMap baseline = service.baseline(w);
			if (baseline == null || baseline.revId() <= 0L)
			{
				continue;
			}
			final Map<String, Object> one = new LinkedHashMap<>();
			one.put("thenDay", day(baseline.dataDay()));
			one.put("thenRevId", baseline.revId());
			// Which document shape the map came from (B028, checker 2026-09-09). Without it, a maintainer
			// looking at a window that says "no baseline" cannot tell a file that was never fetched from one
			// this build refused because an older build wrote it - and the refusal is silent by design.
			one.put("schema", baseline.schema());
			m.put(w.label(), one);
		}
		return m;
	}

	/**
	 * M5's {@code portfolio} echo: the bank value line's figures as the service computed them beside the rows -
	 * {@code valueNow} over {@code itemsPriced} of {@code itemsTotal} stacks (the WHOLE bank, the gp band not
	 * applied), and under {@code windows} one object per window with a baseline, keyed by label, carrying
	 * {@code thenDay} (ISO-8601), {@code valueThen}, {@code valueNowCovered}, {@code deltaGp}, {@code deltaPct}
	 * (untruncated; absent when {@code valueThen} is 0) and {@code itemsCovered} - the stacks priced on BOTH days,
	 * which is the basis of the three sums (M1). A live session sums {@code unit x qty} over the bank file with
	 * RuneLite's price table and expects {@code valueNow} to the gp (M6 step 2). Before the first computation the
	 * figures are zeroes and {@code windows} is empty.
	 *
	 * <p><b>Addendum P puts the cash beside it</b> (P1): {@code currencyGp} is coins + 1,000 x platinum tokens as
	 * {@link BankReader} measured them into {@link BankSnapshot#currencyGp}, and it is a SUMMAND of
	 * {@code valueNow} rather than a figure to add to it - the headline on the card is the whole bank, stacks and
	 * cash together. It is also inside every window's {@code valueThen} and {@code valueNowCovered} for the same
	 * reason, and outside {@code deltaGp}, which cash cannot move; a live session that wants the stacks alone
	 * subtracts it. {@code itemsPriced} / {@code itemsTotal} count STACKS, so cash is in neither - a bank of
	 * nothing but coins reads {@code valueNow == currencyGp} over 0 of 0. Always present, 0 when there is none, so
	 * {@code jq .result.portfolio.currencyGp} never answers null on a real bank.
	 */
	private static Map<String, Object> portfolioJson(@Nullable PriceService.Status s)
	{
		final PortfolioSummary p = s == null || s.portfolio() == null ? PortfolioSummary.EMPTY : s.portfolio();
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("valueNow", p.valueNow());
		m.put("currencyGp", p.currencyGp());
		m.put("itemsPriced", p.itemsPriced());
		m.put("itemsTotal", p.itemsTotal());
		final Map<String, Object> windows = new LinkedHashMap<>();
		for (MovementWindow w : MovementWindow.values())
		{
			final WindowMove move = p.move(w);
			if (move == null)
			{
				continue;
			}
			final Map<String, Object> one = new LinkedHashMap<>();
			one.put("thenDay", day(move.thenDay()));
			one.put("valueThen", move.valueThen());
			one.put("valueNowCovered", move.valueNowCovered());
			one.put("deltaGp", move.deltaGp());
			one.put("deltaPct", move.deltaPct());
			one.put("itemsCovered", move.itemsCovered());
			windows.put(w.label(), one);
		}
		m.put("windows", windows);
		return m;
	}

	/**
	 * A calendar day as ISO-8601, or null - which the injected Gson leaves OUT of the object, so an unknown day
	 * reads as {@code null} to {@code jq} rather than as a plausible-looking date.
	 */
	@Nullable
	private static String day(@Nullable LocalDate date)
	{
		return date == null ? null : date.toString();
	}

	/**
	 * One row. {@code qty} is the whole stack, and since addendum Y (Y1) a row carries the SPLIT behind it -
	 * {@code bankQty}, {@code invQty}, {@code wornQty}, which sum to {@code qty} - but only while the
	 * {@code inventory} switch is on, because with it off there is nothing split to say and the three would
	 * read 0 / 0 / 0 on every row of a bank that has never been merged. They are the numbers the row's own
	 * tooltip prints as "3 in bank, 1 in inventory, 1 worn", so a script can assert the sentence without a
	 * screenshot.
	 *
	 * @param row     the row, or null
	 * @param carried whether the carried switch is on, i.e. whether the split is worth printing
	 */
	private static Map<String, Object> rowJson(@Nullable MovementRow row, boolean carried)
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		if (row == null)
		{
			return m;
		}
		m.put("id", row.id());
		m.put("name", row.name());
		m.put("qty", row.quantity());
		if (carried)
		{
			m.put("bankQty", row.bankQuantity());
			m.put("invQty", row.inventoryQuantity());
			m.put("wornQty", row.wornQuantity());
		}
		m.put("unit", row.unitPrice());
		m.put("then", row.thenPrice());
		m.put("gp", row.deltaGp());
		m.put("pct", row.deltaPct());
		m.put("source", String.valueOf(row.source()));
		return m;
	}

	// ---------------------------------------------------------------- the picture

	/**
	 * Prints {@code parts} into ONE PNG under {@code dir}, stacked top to bottom in the order given, and
	 * answers the file with the size of the picture in it. {@code printAll}, not {@code paint}: it is Swing's
	 * own printing path and walks the whole tree synchronously, so every child comes out too. The shape is
	 * {@code com.lootandbeam.ui.DevCommands.writeShot}, which is package-private in its own package and so
	 * cannot be shared.
	 *
	 * <p><b>Why a stack and not one component.</b> {@link BankPriceMovementPanel} is a
	 * {@code PluginPanel(false)}, so RuneLite sizes it to the room the sidebar has and the rows live inside a
	 * scroll pane: printing the panel prints the VIEWPORT, which is the slice the user can see and nothing
	 * below it. {@code shotComponents()} hands over the header and the scroll pane's VIEW instead, and
	 * stacking them gives the whole sidebar whatever the scroll bar is doing.
	 *
	 * <p><b>Every part must already have a size</b>, which in practice means the sidebar must be open: a part
	 * with no width or height is an {@code IOException}, not something to lay out here. {@link #shot} refuses
	 * before it gets here; this is the backstop for every other caller.
	 */
	static Shot writeShot(@Nullable List<Component> parts, File dir, @Nullable String name) throws IOException
	{
		final List<Component> shown = new ArrayList<>();
		for (Component c : parts == null ? Collections.<Component>emptyList() : parts)
		{
			if (c != null)
			{
				shown.add(c);
			}
		}
		if (shown.isEmpty())
		{
			throw new IOException("nothing to print");
		}
		final List<Dimension> sizes = new ArrayList<>();
		int w = 0;
		int h = 0;
		for (Component c : shown)
		{
			final Dimension d = c.getSize();
			if (d.width <= 0 || d.height <= 0)
			{
				throw new IOException("part has no size: " + c.getClass().getSimpleName());
			}
			sizes.add(d);
			w = Math.max(w, d.width);
			h += d.height;
		}
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		try
		{
			// The parts need not be the same width (the content loses 17 px to the scroll bar, the header does
			// not), so the ground is painted first: an unpainted band would come out black.
			final Color bg = shown.get(0).getBackground();
			g.setColor(bg == null ? Color.BLACK : bg);
			g.fillRect(0, 0, w, h);
			int y = 0;
			for (int i = 0; i < shown.size(); i++)
			{
				final Dimension d = sizes.get(i);
				final Graphics2D part = (Graphics2D) g.create(0, y, d.width, d.height);
				try
				{
					shown.get(i).printAll(part);
				}
				finally
				{
					part.dispose();
				}
				y += d.height;
			}
		}
		finally
		{
			g.dispose();
		}
		if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory())
		{
			throw new IOException("could not create " + dir);
		}
		final String base = name == null || name.trim().isEmpty()
			? "panel" : name.trim().replaceAll("[^A-Za-z0-9_-]", "_");
		final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ENGLISH).format(new Date());
		File out = new File(dir, base + "-" + stamp + ".png");
		for (int n = 2; out.exists(); n++)
		{
			out = new File(dir, base + "-" + stamp + "-" + n + ".png");
		}
		ImageIO.write(img, "png", out);
		return new Shot(out, w, h);
	}

	/** One component on its own, for a caller that is not the sidebar's own {@code shot}. */
	static File writeShot(Component c, File dir, @Nullable String name) throws IOException
	{
		return writeShot(Arrays.asList(c), dir, name).file;
	}

	/** A written shot: the file, and the size of the picture in it (what the {@code shot} answer reports). */
	static final class Shot
	{
		final File file;
		final int width;
		final int height;

		Shot(File file, int width, int height)
		{
			this.file = file;
			this.width = width;
			this.height = height;
		}
	}

	// ---------------------------------------------------------------- answers

	private static Map<String, Object> ok()
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", true);
		return m;
	}

	private static Map<String, Object> error(String message)
	{
		final Map<String, Object> m = new LinkedHashMap<>();
		m.put("ok", false);
		m.put("error", message);
		return m;
	}

	private String json(Map<String, Object> m)
	{
		return gson.toJson(m);
	}
}
