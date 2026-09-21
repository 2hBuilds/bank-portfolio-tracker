package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.MenuElement;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BankPriceMovementPanel} in the Ticker look (contract C28-C33 as addendum N sections 2 and 4 draw it,
 * {@code docs/bank-price-movement-addendum-N-2026-09-09.md}; the only look, with the hero card's three
 * show / hide switches, per addendum O lines O1-O6, {@code docs/bank-price-movement-addendum-O-2026-09-09.md}):
 * the real panel built off-screen on the EDT over a mocked {@link PriceService} (its listener captured at
 * registration and fired by hand) and a recording {@link BankPriceMovementPanel.Prefs}. Every header row and
 * every row is MEASURED against the 213 px content width
 * ({@link #everyHeaderRowAndEveryRowFitsTheContentWidth}), and the hero card is measured in all eight
 * combinations of its switches ({@link #everyCombinationOfTheSwitchesDrawsTheRightLinesFitsTheCardAndTellsTheTooltip}):
 * Swing clips a too-wide row silently (playbook 7.5).
 */
public class BankPriceMovementPanelTest
{
	private static final int W = Widgets.CONTENT_WIDTH;
	/** The sidebar as the client sizes it: the panel plus the room its own scrollbar takes. */
	private static final int SIDEBAR_WIDTH = net.runelite.client.ui.PluginPanel.PANEL_WIDTH
		+ net.runelite.client.ui.PluginPanel.SCROLLBAR_WIDTH;
	/**
	 * The UTC day the 1 d baseline's guide table belongs to (L7; the calibration doc's revid 15333448 carries
	 * {@code %LAST_UPDATE_F%} "07 September 2026"). A date and not the revision's publication instant, because
	 * that clock is not the data day (L-E).
	 */
	private static final LocalDate THEN_DAY = LocalDate.of(2026, 9, 7);
	/** The L7 header line with a baseline: "Guide prices - &lt;window&gt; vs &lt;day&gt; - Bank as of HH:mm". */
	private static final String STATUS_TEXT = "Guide prices - 1d vs 07 Sep - Bank as of 13:52";
	private static final long PRICES_AT = 1_757_340_300_000L;
	private static final String COOLDOWN = "Refreshed 12 s ago - wait";
	private static final String UNAVAILABLE = "Wiki history down - no movement";
	/**
	 * What the card's hover is, for every fixture in this file that publishes {@link #summary()} (addendum AF):
	 * the bank value to the gp, with thousands separators, and nothing else at all.
	 *
	 * <p>It is composed from the summary rather than quoted, because the promise AF makes is that the hover and
	 * the card's own 28 px figure are two spellings of ONE number - so a change that rounds the hover, or that
	 * moves the figure off {@code valueNow()}, has to turn this red rather than be absorbed by a literal.
	 */
	private static final String VALUE_TIP = MovementMath.formatExact(summary().valueNow())
		+ BankPriceMovementPanel.GP_SUFFIX;
	/** The update line's hover as the fixtures' price clock stamps it (S2). */
	private static final String UPDATE_TIP = BankPriceMovementPanel.updateTooltip(PRICES_AT);
	/**
	 * The view switches with addendum T's live prices OFF - the guide-only sidebar of addenda K to S
	 * ({@code docs/bank-price-movement-addendum-T-2026-09-12.md}, line T8).
	 *
	 * <p>Every assertion in this file that was written before addendum T pins that sidebar, so the tests which
	 * pin its WORDING say so by storing this rather than by taking whatever {@link ViewOptions#DEFAULT} means
	 * today - T1 made the live switch default ON. The switch's own behaviour has its own tests
	 * ({@link #theCardSaysLivePricesAreOnWhileTheSwitchIsOn},
	 * {@link #theCardTooltipCountsTheLiveStacksAndNamesTheLivePrices}), and the promise the two halves make
	 * together is T8's: with the switch off this panel is exactly the panel those addenda left behind.
	 */
	private static final ViewOptions LIVE_OFF = ViewOptions.DEFAULT.withLivePrices(false);
	/**
	 * The view switches with the hover switch ON (addendum AH, deleted by AI and restored narrowed by AJ:
	 * {@code docs/bank-price-movement-addendum-AJ-2026-09-20.md}).
	 *
	 * <p>It is the {@link #LIVE_OFF} of the hovers, and for the same reason. {@code showHoverText} ships OFF -
	 * the one switch in {@link ViewOptions#DEFAULT} whose default is the quieter sidebar - so a test that pins
	 * what a hover SAYS has to name the switch it is reading under rather than inherit today's default, or
	 * there is no hover on the component to read at all. Every such test goes through
	 * {@link #buildWithHovers()}; that the default really is silent is pinned by the addendum AJ section
	 * instead ({@link #theCardsHoverIsSilentUntilTheSwitchIsTurnedOn}).
	 *
	 * <p><b>AH3 widened it from two hovers to every one of them, and AJ narrowed it back by exactly one.</b>
	 * AH as first built spared the tooltips that explain a CONTROL, and the user, on that build: "there are
	 * still some things that show hover text even when its 'off' please fix this and make sure there is no
	 * hover text at all unless it is on". So the sort button, the Refresh link, the update line, the gear and
	 * its items, the chips, the band button and the preset boxes go quiet with the switch too - which is why
	 * every test below that reads one of THEIR tooltips builds through {@link #buildWithHovers()} as well. The
	 * item ROWS are the one thing outside its reach, because addendum AI moved a row's description out of the
	 * hover and into the cell, so a row is silent at either setting
	 * ({@link #aRowIsSilentUnderEitherSettingAndStillOpensOnAClick}).
	 */
	private static final ViewOptions HOVERS_ON = ViewOptions.DEFAULT.withShowHoverText(true);

	private ItemManager itemManager;
	private PriceService service;
	private RecordingPrefs prefs;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	/**
	 * The plugin's config, as a memory: what was loaded and every save in order - the filter, the hero switches,
	 * the view switches since addendum Q, the three price presets since addendum Z and the fold's own state since
	 * addendum AA.
	 */
	private static final class RecordingPrefs implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		RowFilter stored;
		@Nullable
		HeroVisibility storedHero;
		@Nullable
		ViewOptions storedOptions;
		@Nullable
		BandPresets storedPresets;
		/** AA1: null is "nothing stored", which the panel reads as OPEN; {@link #setUp} stores FALSE, see there. */
		@Nullable
		Boolean storedFoldOpen;
		final List<RowFilter> saves = new ArrayList<>();
		final List<HeroVisibility> heroSaves = new ArrayList<>();
		final List<ViewOptions> optionSaves = new ArrayList<>();
		final List<BandPresets> presetSaves = new ArrayList<>();
		final List<Boolean> foldSaves = new ArrayList<>();

		@Override
		@Nullable
		public RowFilter load()
		{
			return stored;
		}

		@Override
		public void save(RowFilter filter)
		{
			saves.add(filter);
		}

		@Override
		@Nullable
		public HeroVisibility loadHero()
		{
			return storedHero;
		}

		@Override
		public void saveHero(HeroVisibility visibility)
		{
			heroSaves.add(visibility);
		}

		@Override
		@Nullable
		public ViewOptions loadOptions()
		{
			return storedOptions;
		}

		@Override
		public void saveOptions(ViewOptions options)
		{
			optionSaves.add(options);
		}

		@Override
		@Nullable
		public BandPresets loadPresets()
		{
			return storedPresets;
		}

		@Override
		public void savePresets(BandPresets presets)
		{
			presetSaves.add(presets);
		}

		@Override
		@Nullable
		public Boolean loadFoldOpen()
		{
			return storedFoldOpen;
		}

		@Override
		public void saveFoldOpen(boolean open)
		{
			foldSaves.add(open);
		}
	}

	/**
	 * <b>The fold starts CLOSED in this file</b> (addendum AA, line AA1): every assertion written before AA -
	 * the header's component list, the fold being ADDED on a tap, the describe strings - pins the sidebar whose
	 * fold was shut until the band button was pressed, and says so here rather than by taking whatever the
	 * shipped default is today. It is the {@link #LIVE_OFF} of the fold.
	 *
	 * <p>AA's own default is pinned where it belongs, by the tests that build with NOTHING stored:
	 * {@link #theFoldStandsOpenOnAFreshInstall}, {@link #aStoredFoldStateIsWhatTheSidebarOpensOn} and
	 * {@link #theRendererDrawsTheOpenFoldAndCanStillDrawTheClosedOne}.
	 */
	@Before
	public void setUp() throws Exception
	{
		itemManager = mock(ItemManager.class);
		service = mock(PriceService.class);
		prefs = new RecordingPrefs();
		prefs.storedFoldOpen = Boolean.FALSE;
	}

	/**
	 * Builds the panel on the EDT and captures the listener it registered.
	 *
	 * <p>The panel's clock is pinned to {@link #PRICES_AT} - the instant every fixture here is stamped with - so
	 * that "was this bank captured TODAY?" ({@code provenanceText}) has one answer whenever this suite is run.
	 * The fixtures' banks are a minute older than that, i.e. the same day, which is the state the footnote was
	 * written for; {@link #aBankFromAnEarlierDayIsStampedWithItsDayNotAClock} is the other case.
	 */
	private void build() throws Exception
	{
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(itemManager, service, prefs);
			panel.setClock(() -> PRICES_AT);
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
		assertNotNull(listener);
	}

	/**
	 * {@link #build()} with addendum AH's hover switch turned ON in the stored config, over whatever else the
	 * test has already put there (so a test that pins the guide-only card with {@link #LIVE_OFF} keeps it).
	 *
	 * <p>Every test that asserts what the card's hover or a CONTROL's hover says builds through this rather
	 * than through {@link #build()}: since AH3 the switch governs both - the card's gp figure, and every
	 * tooltip that explains a control - so a panel built on the defaults carries no tooltip on either and there
	 * is nothing to read. A test about an item ROW does NOT need it and must not imply that it does: since
	 * addendum AI a row is silent at either setting. The switch is seeded through the PREFS seam, which is the
	 * plugin's own road in
	 * ({@code Prefs.loadOptions} reads the config key), so nothing is saved back and {@code prefs.optionSaves}
	 * stays empty.
	 */
	private void buildWithHovers() throws Exception
	{
		prefs.storedOptions = hovers(prefs.storedOptions == null ? ViewOptions.DEFAULT : prefs.storedOptions);
		build();
	}

	/** {@code options} with addendum AH's hover switch on - for a test that hands the panel a value directly. */
	private static ViewOptions hovers(ViewOptions options)
	{
		return options.withShowHoverText(true);
	}

	private void publish(List<MovementRow> rows, PriceService.Status status) throws Exception
	{
		onEdt(() -> listener.onRows(rows, status));
	}

	// ---- fixtures

	private static PriceService.Status status(boolean loggedIn, boolean bankLoaded, int bankItems, int totalRows,
		MovementWindow window, @Nullable String problem, String text, long pricesAtMillis)
	{
		return status(loggedIn, bankLoaded, bankItems, totalRows, window, problem, text, pricesAtMillis, THEN_DAY);
	}

	/**
	 * {@code Status} is a mock, not a real one, on purpose: every addendum so far has added fields to its
	 * constructor and another agent owns that class, so a test that named the constructor would pin an argument
	 * list this file has no business pinning. Every getter the panel reads is stubbed here instead.
	 * ({@code Status} is deliberately NOT final for exactly this - see its javadoc.) {@code headerText()} answers
	 * the same sentence as {@code text()}, which is what the hero card's tooltip carries (N 3.1).
	 */
	private static PriceService.Status status(boolean loggedIn, boolean bankLoaded, int bankItems, int totalRows,
		MovementWindow window, @Nullable String problem, String text, long pricesAtMillis,
		@Nullable LocalDate thenDay)
	{
		return status(loggedIn, bankLoaded, bankItems, totalRows, window, problem, kindOf(problem), text,
			pricesAtMillis, thenDay);
	}

	/** {@link #status} with the problem's KIND said outright - the panel reads it and never the wording (S1). */
	private static PriceService.Status status(boolean loggedIn, boolean bankLoaded, int bankItems, int totalRows,
		MovementWindow window, @Nullable String problem, PriceService.ProblemKind kind, String text,
		long pricesAtMillis, @Nullable LocalDate thenDay)
	{
		final PriceService.Status s = mock(PriceService.Status.class);
		when(s.loggedIn()).thenReturn(loggedIn);
		when(s.bankLoaded()).thenReturn(bankLoaded);
		when(s.bankItems()).thenReturn(bankItems);
		when(s.totalRows()).thenReturn(totalRows);
		when(s.window()).thenReturn(window);
		when(s.problem()).thenReturn(problem);
		when(s.problemKind()).thenReturn(kind);
		when(s.text()).thenReturn(text);
		when(s.headerText()).thenReturn(text);
		when(s.pricesAtMillis()).thenReturn(pricesAtMillis);
		when(s.bankAtMillis()).thenReturn(pricesAtMillis == 0L ? 0L : pricesAtMillis - 60_000L);
		when(s.baselineLoaded()).thenReturn(true);
		when(s.thenDay()).thenReturn(thenDay);
		return s;
	}

	/**
	 * What kind of problem a fixture's sentence is: the pairing {@code PriceService} makes where it WRITES the
	 * sentence (K7, L11), mirrored here so an ordinary fixture answers what a real status would. The panel never
	 * makes this deduction - it reads {@code problemKind()} - which is why the fixture that proves it takes the
	 * kind explicitly instead.
	 */
	private static PriceService.ProblemKind kindOf(@Nullable String problem)
	{
		if (problem == null)
		{
			return PriceService.ProblemKind.NONE;
		}
		for (MovementWindow w : MovementWindow.values())
		{
			if (problem.equals(PriceService.problemNoHistory(w)))
			{
				return PriceService.ProblemKind.NO_HISTORY;
			}
			if (problem.equals(PriceService.problemHistoryPending(w)))
			{
				return PriceService.ProblemKind.PENDING;
			}
		}
		return problem.equals(COOLDOWN) ? PriceService.ProblemKind.COOLDOWN : PriceService.ProblemKind.HISTORY_DOWN;
	}

	private static PriceService.Status listed(int bankItems, int totalRows)
	{
		return status(true, true, bankItems, totalRows, MovementWindow.D1, null, STATUS_TEXT, PRICES_AT);
	}

	/** A LIST status carrying {@code problem}, for the problem row. */
	private static PriceService.Status listedWithProblem(String problem, MovementWindow window, @Nullable LocalDate thenDay)
	{
		return status(true, true, 3, 3, window, problem, STATUS_TEXT, PRICES_AT, thenDay);
	}

	/**
	 * {@link #listedWithProblem} carrying the portfolio as well - the hero card speaking AND the problem row
	 * under it, which is the state the whole-tree hover walk needs: it is the only publish that puts all four
	 * of {@link Widgets#setFitted}'s labels on screen at once.
	 */
	private static PriceService.Status listedWithProblemAndPortfolio(String problem)
	{
		final PriceService.Status s = listedWithProblem(problem, MovementWindow.D1, THEN_DAY);
		when(s.portfolio()).thenReturn(summary());
		return s;
	}

	/** {@link #listedWithProblem} with the kind said outright, whatever the sentence reads like. */
	private static PriceService.Status listedWithProblem(String problem, PriceService.ProblemKind kind,
		MovementWindow window, @Nullable LocalDate thenDay)
	{
		return status(true, true, 3, 3, window, problem, kind, STATUS_TEXT, PRICES_AT, thenDay);
	}

	/**
	 * n rows, ids 1..n, "Item i" at i x 1,000 gp, every one with a move. The source is left null - which
	 * {@link MovementRow} normalises to {@code NONE} - because addendum K6 rewrote that enum and the panel
	 * never reads it.
	 */
	private static List<MovementRow> rows(int n)
	{
		final List<MovementRow> out = new ArrayList<>(n);
		for (int i = 1; i <= n; i++)
		{
			final long unit = i * 1_000L;
			out.add(new MovementRow(i, "Item " + i, i, i > 1, unit, unit - 100L, 100L, 100.0 * 100 / (unit - 100), unit * i,
				null));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * {@link #rows} as the half-hourly price recheck publishes them again: the SAME items - same ids, same names,
	 * same quantities - one gp dearer each.
	 *
	 * <p>It is the fixture addendum AG's tests are built on, because it is the publish that nobody asked for. The
	 * list differs, so {@code onRows} rebuilds the page from new {@link MovementRowPanel} instances; nothing the
	 * reader did caused it; and a row that held its own clicked/unclicked flag would fold itself up right here.
	 */
	private static List<MovementRow> repriced(int n)
	{
		final List<MovementRow> out = new ArrayList<>(n);
		for (int i = 1; i <= n; i++)
		{
			final long unit = i * 1_000L + 1L;
			out.add(new MovementRow(i, "Item " + i, i, i > 1, unit, unit - 101L, 101L, 101.0 * 100 / (unit - 101),
				unit * i, null));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * N6's widest row fixtures: a 30-character name over a "2,147m" price with a stack of 28,000 and "+100.0%",
	 * a "-99.9%" fall, and a row with no price at all.
	 */
	private static List<MovementRow> wideRows()
	{
		final String thirty = "Karambwan vessel (baited) long";
		assertEquals(30, thirty.length());
		return Arrays.asList(
			new MovementRow(1, thirty, 28_000, true, 2_147_000_000L, 1_073_500_000L, 1_073_500_000L, 100.0d, Long.MAX_VALUE, null),
			new MovementRow(2, "Ancient ceremonial legs", 1, false, 100_000L, 999_999_999L, -999_899_999L, -99.99d, 100_000L, null),
			new MovementRow(3, "Green hat", 1, false, null, null, null, null, 0L, null));
	}

	/**
	 * The M4 example's figures: 1.23b over 519 of 538 stacks; +12.4m (+1.0 %) on 1d, flat on 7d, -3.1m (-0.2 %) on
	 * 30d, and no baseline for 90d or 180d.
	 */
	private static PortfolioSummary summary()
	{
		final Map<MovementWindow, WindowMove> moves = new EnumMap<>(MovementWindow.class);
		moves.put(MovementWindow.D1, new WindowMove(MovementWindow.D1, THEN_DAY, 1_222_167_890L, 1_234_567_890L,
			12_400_000L, 1.0146d, 512));
		moves.put(MovementWindow.D7, new WindowMove(MovementWindow.D7, THEN_DAY.minusDays(6), 1_234_567_890L,
			1_234_567_890L, 0L, 0.0d, 500));
		moves.put(MovementWindow.D30, new WindowMove(MovementWindow.D30, LocalDate.of(2026, 8, 9), 1_237_667_890L,
			1_234_567_890L, -3_100_000L, -0.2505d, 498));
		return new PortfolioSummary(1_234_567_890L, 519, 538, moves);
	}

	/**
	 * {@link #summary()} with coins and platinum tokens on top of it (P1): the same stacks and the same moves,
	 * {@code currencyGp} gp of cash. {@code valueNow()} is then the two added, which is what the card prints.
	 */
	private static PortfolioSummary summaryWithCash(long currencyGp)
	{
		final PortfolioSummary base = summary();
		return new PortfolioSummary(base.valueStacks(), base.itemsPriced(), base.itemsTotal(), base.moves(), currencyGp);
	}

	/**
	 * {@link #summary()} with the count addendum T's card prints (T5): how many of the priced stacks were valued
	 * at a live traded mid rather than at a guide price. Everything else is the same bank.
	 */
	private static PortfolioSummary summaryWithLive(int liveRows)
	{
		final PortfolioSummary base = summary();
		return new PortfolioSummary(base.valueStacks(), base.itemsPriced(), base.itemsTotal(), base.moves(),
			base.currencyGp(), liveRows);
	}

	/** The widest figures N6 names for the card: a 12-digit total and "-99.9%", with "-999.9m"-class gp moves. */
	private static PortfolioSummary hugeSummary()
	{
		final Map<MovementWindow, WindowMove> moves = new EnumMap<>(MovementWindow.class);
		moves.put(MovementWindow.D1, new WindowMove(MovementWindow.D1, LocalDate.of(2026, 12, 31), 999_999_999_999L,
			999_999_999L, -999_000_000_000L, -99.9d, 2_000_000_000));
		return new PortfolioSummary(999_999_999_999L, 1_999_999_999, 2_000_000_000, moves);
	}

	/** A LIST status whose mocked {@code portfolio()} answers the given summary. */
	private static PriceService.Status listedWith(PortfolioSummary summary)
	{
		final PriceService.Status s = listed(538, 519);
		when(s.portfolio()).thenReturn(summary);
		return s;
	}

	/**
	 * A LIST status carrying addendum U's live calendar: how many stacks this publish put on the traded series, the
	 * UTC day of the live snapshot they were priced from, and the day each window's traded bucket actually holds.
	 *
	 * <p>A window left out of {@code windowDays} has no traded bucket, which is the state the card falls back to the
	 * guide day for; a status built by {@link #listedWith} alone has no live calendar at all, which is every status
	 * written before addendum U.
	 */
	private static PriceService.Status listedWithLive(PortfolioSummary summary, int liveRows,
		@Nullable LocalDate liveDay, @Nullable Map<MovementWindow, LocalDate> windowDays)
	{
		final PriceService.Status s = listedWith(summary);
		when(s.live()).thenReturn(new PriceService.Status.LiveStatus(PRICES_AT, 4_535, liveRows,
			Math.max(0, summary.itemsPriced() - liveRows), 0, liveDay, windowDays));
		return s;
	}

	/** One window's traded day for {@link #listedWithLive}; every other window has no bucket. */
	private static Map<MovementWindow, LocalDate> liveDays(MovementWindow window, @Nullable LocalDate day)
	{
		final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
		days.put(window, day);
		return days;
	}

	/** A LIST status, logged in or out, whose mocked {@code portfolio()} answers the given summary. */
	private static PriceService.Status statusWith(boolean loggedIn, PortfolioSummary summary)
	{
		final PriceService.Status s = status(loggedIn, true, 538, 519, MovementWindow.D1, null, STATUS_TEXT, PRICES_AT);
		when(s.portfolio()).thenReturn(summary);
		return s;
	}

	/** The eight combinations of the three switches, bit 0 = value, bit 1 = gp, bit 2 = pct (O6). */
	private static HeroVisibility combo(int i)
	{
		return HeroVisibility.of((i & 1) != 0, (i & 2) != 0, (i & 4) != 0);
	}

	// ---- construction and the saved filter

	@Test
	public void startsOnTheLoginCardWithTheSavedFilterPaintedAndAnEmptyHeader() throws Exception
	{
		prefs.stored = new RowFilter(100L, 5_000_000L, SortMode.GP_MOVE, false, MovementWindow.D30);
		build();
		onEdt(() ->
		{
			assertEquals(BankPriceMovementPanel.CARD_LOGIN, panel.card());
			assertEquals(prefs.stored, panel.filter());
			assertEquals("a fresh prefs shows every hero figure", HeroVisibility.ALL, panel.heroVisibility());
			assertTrue(Widgets.isLit(panel.windowChip(MovementWindow.D30)));
			assertFalse(Widgets.isLit(panel.windowChip(MovementWindow.D1)));
			assertFalse(Widgets.isLit(panel.windowChip(MovementWindow.D7)));
			assertEquals("the sort button names the saved COLUMN", "gp change", panel.sortButton().getText());
			assertTrue("...and its arrow is the saved direction (W2)",
				sameIcon(Widgets.triangle(false), panel.sortButton().getIcon()));
			assertEquals("100", panel.minField().getText());
			assertEquals("5,000,000", panel.maxField().getText());
			assertEquals("LOGIN: the header is emptied (N section 3 §3)", 0, panel.header().getComponentCount());
			assertFalse(panel.heroShowing());
			assertFalse(panel.foldOpen());
			assertTrue("construction saves nothing", prefs.saves.isEmpty());
			assertTrue(prefs.heroSaves.isEmpty());
		});
		verify(service, never()).setFilter(any());
		verify(service, never()).setVisible(anyBoolean());
	}

	@Test
	public void freshInstallUsesTheDefaultFilterAndShowsThePlaceholders() throws Exception
	{
		build();
		onEdt(() ->
		{
			assertEquals(RowFilter.DEFAULT, panel.filter());
			assertTrue(Widgets.isLit(panel.windowChip(MovementWindow.D1)));
			assertEquals("Percent change", panel.sortButton().getText());
			assertEquals("", panel.minField().getText());
			assertEquals("", panel.maxField().getText());
			assertEquals("the captions went; the placeholders say which is which (N 3.5)", "min gp", panel.minField().placeholder());
			assertEquals("max gp", panel.maxField().placeholder());
			assertTrue(panel.minField().placeholderShowing());
			assertTrue(panel.maxField().placeholderShowing());
			assertFalse(Widgets.isMarkedInvalid(panel.minField()));
			assertEquals("the fields are 90 px", BankPriceMovementPanel.FIELD_WIDTH, panel.minField().getPreferredSize().width);
		});
	}

	/** O2: the initial switches come from the config, through the prefs seam; the menu ticks agree. */
	@Test
	public void theInitialHeroVisibilityComesFromThePrefs() throws Exception
	{
		prefs.storedHero = HeroVisibility.of(false, true, false);
		build();
		publish(rows(2), listedWith(summary()));
		onEdt(() ->
		{
			assertEquals(HeroVisibility.of(false, true, false), panel.heroVisibility());
			assertFalse("the total is off the card", panel.shows(panel.totalLabel()));
			assertTrue(panel.shows(panel.moveLine()));
			assertTrue(panel.shows(panel.deltaLabel()));
			assertFalse("the percent is off the move line", panel.shows(panel.pctLabel()));
			assertFalse(panel.showValueItem().isSelected());
			assertTrue(panel.showGpItem().isSelected());
			assertFalse(panel.showPctItem().isSelected());
			assertTrue(panel.describe(), panel.describe().contains("\"hero\":{\"value\":false,\"gp\":true,\"pct\":false}"));
			assertTrue("nothing is written back on construction", prefs.heroSaves.isEmpty());
		});
	}

	@Test
	public void seedsFromTheServiceWhenItAlreadyHasState() throws Exception
	{
		// Built BEFORE the stubbing: a mock made inside thenReturn(...) trips Mockito's unfinished-stubbing
		// check (playbook 7.6).
		final PriceService.Status seeded = listed(2, 2);
		when(service.currentStatus()).thenReturn(seeded);
		when(service.currentRows()).thenReturn(rows(2));
		build();
		onEdt(() ->
		{
			assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());
			assertEquals(2, panel.rowPanels().size());
			assertEquals(2, panel.totalRows());
			assertTrue(panel.heroShowing());
		});
	}

	// ---- cards

	@Test
	public void cardsFollowTheStatus() throws Exception
	{
		build();
		publish(Collections.emptyList(), status(false, false, 0, 0, MovementWindow.D1, null, "No bank yet", 0L));
		assertEquals(BankPriceMovementPanel.CARD_LOGIN, panel.card());

		publish(Collections.emptyList(), status(true, false, 0, 0, MovementWindow.D1, null, "No bank yet", 0L));
		assertEquals(BankPriceMovementPanel.CARD_NO_BANK, panel.card());

		publish(Collections.emptyList(), listed(10, 0));
		assertEquals(BankPriceMovementPanel.CARD_EMPTY, panel.card());

		publish(rows(3), listed(10, 3));
		assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());

		publish(rows(3), status(false, true, 10, 3, MovementWindow.D1, null, "Bank as of 13:52 (logged out)", 0L));
		assertEquals("the last bank stays on screen after logout", BankPriceMovementPanel.CARD_LIST, panel.card());
	}

	/** N section 3 §6: the EMPTY card says why, and offers "Clear price range" only when a band did it. */
	@Test
	public void emptyCardSaysWhyAndOffersClearPriceRangeOnlyForABand() throws Exception
	{
		build();
		publish(Collections.emptyList(), listed(0, 0));
		assertTrue(texts(panel.shotComponents().get(1)).toString().contains(BankPriceMovementPanel.NO_TRADEABLES_TEXT));
		assertFalse("no band, no button", panel.clearBandShowing());
		assertTrue("the header stays whole on EMPTY", panel.heroShowing());

		publish(Collections.emptyList(), listed(10, 0));
		assertTrue(texts(panel.shotComponents().get(1)).toString().contains(BankPriceMovementPanel.NO_PRICES_TEXT));
		assertFalse(panel.clearBandShowing());

		onEdt(() -> panel.applyMin("100k"));
		publish(Collections.emptyList(), listed(10, 0));
		String shown = texts(panel.shotComponents().get(1)).toString();
		assertTrue(shown, shown.contains(BankPriceMovementPanel.EMPTY_TEXT));
		assertTrue(shown, shown.contains("Your band is 100k and up"));
		assertTrue(panel.clearBandShowing());
		assertEquals(BankPriceMovementPanel.CLEAR_BAND_TEXT, panel.clearBandButton().getText());
		assertTrue(SwingUtilities.isDescendingFrom(panel.clearBandButton(), panel.shotComponents().get(1)));
		assertEquals("the band button states the band that emptied the list", "100k+", panel.bandTarget().getText());

		onEdt(() -> panel.applyMax("5m"));
		publish(Collections.emptyList(), listed(10, 0));
		shown = texts(panel.shotComponents().get(1)).toString();
		assertTrue(shown, shown.contains("Your band is from 100k to 5m"));

		onEdt(() -> panel.applyMin(""));
		publish(Collections.emptyList(), listed(10, 0));
		shown = texts(panel.shotComponents().get(1)).toString();
		assertTrue(shown, shown.contains("Your band is up to 5m"));

		onEdt(() -> panel.clearBandButton().doClick(0));
		assertEquals("the button clears both bounds", RowFilter.DEFAULT, panel.filter());
		assertEquals("", panel.minField().getText());
		assertEquals("", panel.maxField().getText());
		verify(service).setFilter(RowFilter.DEFAULT);

		// A bank with no tradeables at all gets no button even under a band: there is nothing to unhide.
		onEdt(() -> panel.applyMin("1m"));
		publish(Collections.emptyList(), listed(0, 0));
		assertTrue(texts(panel.shotComponents().get(1)).toString().contains(BankPriceMovementPanel.NO_TRADEABLES_TEXT));
		assertFalse(panel.clearBandShowing());
	}

	// ---- the header's anatomy

	/** N section 3 §3 / section 4: LOGIN and NO_BANK empty the header; EMPTY and LIST carry the card and the control row. */
	@Test
	public void headerIsEmptiedWithoutABankAndWholeWithOne() throws Exception
	{
		build();
		assertEquals(0, panel.header().getComponentCount());

		publish(Collections.emptyList(), status(true, false, 0, 0, MovementWindow.D1, null, "No bank yet", 0L));
		assertEquals("NO_BANK: nothing to value or order", 0, panel.header().getComponentCount());
		assertFalse(panel.heroShowing());

		publish(Collections.emptyList(), listed(10, 0));
		assertEquals(BankPriceMovementPanel.CARD_EMPTY, panel.card());
		assertEquals(Arrays.asList(panel.hero(), panel.controlRow()), Arrays.asList(panel.header().getComponents()));
		assertTrue(panel.heroShowing());

		final JPanel hero = panel.hero();
		publish(rows(3), listed(3, 3));
		assertSame("the same card, not a new one per publish", hero, panel.hero());
		assertEquals(2, panel.header().getComponentCount());

		publish(rows(3), listedWithProblem(COOLDOWN, MovementWindow.D1, THEN_DAY));
		assertEquals(Arrays.asList(panel.hero(), panel.controlRow(), panel.problemLabel()),
			Arrays.asList(panel.header().getComponents()));

		onEdt(() -> panel.toggleFold());
		assertEquals("the fold sits between the control row and the problem row",
			Arrays.asList(panel.hero(), panel.controlRow(), panel.fold(), panel.problemLabel()),
			Arrays.asList(panel.header().getComponents()));

		publish(Collections.emptyList(), status(true, false, 0, 0, MovementWindow.D1, null, "No bank yet", 0L));
		assertEquals("the bank went away: everything goes with it", 0, panel.header().getComponentCount());
		assertTrue("...but the fold remembers it was open", panel.foldOpen());
		publish(rows(3), listed(3, 3));
		assertEquals(Arrays.asList(panel.hero(), panel.controlRow(), panel.fold()),
			Arrays.asList(panel.header().getComponents()));
	}

	/** N section 4: the chips live inside the card, five 38 x 22 transparent cells across its 191 px. */
	@Test
	public void theChipsLiveInsideTheCard() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() ->
		{
			assertTrue(SwingUtilities.isDescendingFrom(panel.chipRow(), panel.hero()));
			assertSame(panel.stripHolder(), panel.chipRow().getParent());
			assertEquals(new Dimension(BankPriceMovementPanel.CARD_INNER, BankPriceMovementPanel.CHIP_HEIGHT),
				panel.chipRow().getPreferredSize());
			for (MovementWindow w : MovementWindow.values())
			{
				final JLabel chip = panel.windowChip(w);
				assertEquals(new Dimension(BankPriceMovementPanel.CHIP_WIDTH, BankPriceMovementPanel.CHIP_HEIGHT),
					chip.getPreferredSize());
				assertFalse("a chip is transparent so the card shows through", chip.isOpaque());
			}
			panel.toggleFold();
			assertEquals(Arrays.asList(panel.hero(), panel.controlRow(), panel.fold()), Arrays.asList(panel.header().getComponents()));
		});
	}

	// ---- the hero card

	/** N 4.2: the card - the triangle, the gp and the percent as a pair, the chips, the provenance footnote. */
	@Test
	public void heroShowsTheTriangleTheGpThePercentAndTheFootnote() throws Exception
	{
		build();
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			assertEquals(BankPriceMovementPanel.VALUE_TITLE, panel.captionLabel().getText());
			assertEquals(11f, panel.captionLabel().getFont().getSize2D(), 0f);
			assertEquals("1.23b", panel.totalLabel().getText());
			assertEquals(Color.WHITE, panel.totalLabel().getForeground());
			assertEquals(28f, panel.totalLabel().getFont().getSize2D(), 0f);
			assertTrue(panel.totalLabel().getFont().isBold());
			assertTrue("a rise points up", sameIcon(Widgets.triangleUp(ColorScheme.PROGRESS_COMPLETE_COLOR), panel.triangleLabel().getIcon()));
			assertEquals("+12.4m", panel.deltaLabel().getText());
			assertEquals(18f, panel.deltaLabel().getFont().getSize2D(), 0f);
			assertTrue(panel.deltaLabel().getFont().isBold());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, panel.deltaLabel().getForeground());
			assertEquals("+1.0%", panel.pctLabel().getText());
			assertEquals(18f, panel.pctLabel().getFont().getSize2D(), 0f);
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, panel.pctLabel().getForeground());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR.darker(), edgeOf(panel.hero()));
			assertEquals(Widgets.EDGE_WIDTH, edgeWidthOf(panel.hero()));
			final String footnote = panel.footnoteLabel().getText();
			assertTrue(footnote, footnote.startsWith("1d vs 07 Sep - bank "));
			assertFalse(footnote, footnote.contains("logged out"));
			assertEquals(12f, panel.footnoteLabel().getFont().getSize2D(), 0f);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.footnoteLabel().getForeground());
			assertEquals("every line, top to bottom", Arrays.asList(panel.captionRow(), panel.totalRow(), panel.moveLine(),
				panel.stripHolder(), panel.footnoteLabel(), panel.updateLabel()), Arrays.asList(panel.hero().getComponents()));
			assertEquals("the total shares its line with the gear (Q1)",
				Arrays.asList(panel.totalLabel(), panel.gearLabel()), Arrays.asList(panel.totalRow().getComponents()));

			panel.selectWindow(MovementWindow.D30);
			assertTrue("a fall points down", sameIcon(Widgets.triangleDown(ColorScheme.PROGRESS_ERROR_COLOR), panel.triangleLabel().getIcon()));
			assertEquals("the chip alone moves the card: the summary already holds every window (M6 step 3)",
				"-3.1m", panel.deltaLabel().getText());
			assertEquals("-0.2%", panel.pctLabel().getText());
			assertEquals("the FIGURE takes the lifted red (B045)", Widgets.MOVE_DOWN_TEXT, panel.pctLabel().getForeground());
			assertTrue(panel.footnoteLabel().getText(), panel.footnoteLabel().getText().startsWith("30d vs 09 Aug - bank "));
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(), edgeOf(panel.hero()));

			panel.selectWindow(MovementWindow.D7);
			assertNull("no triangle on a zero move", panel.triangleLabel().getIcon());
			assertEquals("0", panel.deltaLabel().getText());
			assertEquals("0.0%", panel.pctLabel().getText());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.pctLabel().getForeground());
			assertEquals("a flat window is a zero move in grey with no edge", ColorScheme.DARKER_GRAY_COLOR, edgeOf(panel.hero()));

			panel.selectWindow(MovementWindow.D180);
			assertNull("no triangle without a baseline", panel.triangleLabel().getIcon());
			assertEquals("no baseline: one dash", MovementMath.DASH, panel.deltaLabel().getText());
			assertEquals("", panel.pctLabel().getText());
			assertTrue(panel.footnoteLabel().getText(), panel.footnoteLabel().getText().startsWith("Guide prices - bank "));
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, edgeOf(panel.hero()));
			assertEquals("the total does not depend on the window", "1.23b", panel.totalLabel().getText());

			// ConfigChanged's path repaints the card too.
			panel.applyFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D1));
			assertEquals("+1.0%", panel.pctLabel().getText());
		});
		// Still on 1d: the logged-out marker replaces the clock in the footnote, whatever the first half says.
		publish(rows(3), status(false, true, 3, 3, MovementWindow.D1, null, STATUS_TEXT, PRICES_AT));
		assertEquals("no summary on this status: the total is 0 and the dash is grey", "0", panel.totalLabel().getText());
		assertEquals(MovementMath.DASH, panel.deltaLabel().getText());
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.deltaLabel().getForeground());
		assertEquals("logged out is said in the footnote, in place of the clock", "Guide prices - logged out", panel.footnoteLabel().getText());
		publish(rows(3), statusWith(false, hugeSummary()));
		assertEquals("1d vs 31 Dec - logged out", panel.footnoteLabel().getText());
	}

	/** N 4.2 / 4.6: the caption never changes; the footnote carries the day, the clock and the logged-out marker. */
	@Test
	public void theCaptionIsFixedAndTheFootnoteCarriesTheProvenance() throws Exception
	{
		build();
		publish(rows(1), status(true, true, 1, 1, MovementWindow.D1, null, STATUS_TEXT, 0L));
		assertEquals(BankPriceMovementPanel.VALUE_TITLE, panel.captionLabel().getText());
		assertEquals("no bank time known: a dash for the clock", "Guide prices - bank -", panel.footnoteLabel().getText());

		publish(rows(1), listedWith(summary()));
		assertEquals(BankPriceMovementPanel.VALUE_TITLE, panel.captionLabel().getText());
		assertTrue(panel.footnoteLabel().getText(), panel.footnoteLabel().getText().startsWith("1d vs 07 Sep - bank "));
		assertFalse(panel.footnoteLabel().getText().endsWith(Widgets.ELLIPSIS));

		final PriceService.Status out = status(false, true, 1, 1, MovementWindow.D1, null, "", 0L);
		final WindowMove move = summary().move(MovementWindow.D1);
		assertEquals("1d vs 07 Sep - logged out", BankPriceMovementPanel.provenanceText(out, MovementWindow.D1, move, PRICES_AT));
		assertEquals("Guide prices - bank -", BankPriceMovementPanel.provenanceText(null, null, null, PRICES_AT));
		assertEquals("Guide prices - logged out", BankPriceMovementPanel.provenanceText(out, MovementWindow.D180, null, PRICES_AT));
		assertTrue(BankPriceMovementPanel.provenanceText(listed(1, 1), MovementWindow.D30, move, PRICES_AT).startsWith("30d vs 07 Sep - bank "));
		assertTrue(BankPriceMovementPanel.provenanceText(listed(1, 1), null, move, PRICES_AT).startsWith("1d vs 07 Sep - bank "));
	}

	/**
	 * B044 / B102: the bank snapshot is remembered per account across sessions, so its stamp has to say WHICH
	 * DAY it is from. On its own day it is the clock it always was; on any other day the clock gives way to the
	 * date, because "bank 09:16" under a headline reading "Bank value" is indistinguishable from this morning
	 * while every quantity, holding and total on that screen is three days old.
	 */
	@Test
	public void aBankFromAnEarlierDayIsStampedWithItsDayNotAClock() throws Exception
	{
		build();
		final long day = 24L * 60 * 60 * 1000;
		final WindowMove move = summary().move(MovementWindow.D1);
		final PriceService.Status same = listed(1, 1);

		assertEquals("captured today: the clock, as before",
			"1d vs 07 Sep - bank " + MovementMath.formatTime(same.bankAtMillis()),
			BankPriceMovementPanel.provenanceText(same, MovementWindow.D1, move, PRICES_AT));
		final String older = BankPriceMovementPanel.provenanceText(same, MovementWindow.D1, move, PRICES_AT + 3 * day);
		assertEquals("three days later: the day the bank was really captured",
			"1d vs 07 Sep - bank " + MovementMath.formatDay(localDay(same.bankAtMillis())), older);
		assertFalse("a three-day-old bank must not read as a time today", older.contains(":"));

		// The stamp itself, at the panel's own rule: no bank is a dash whatever the clock says.
		assertEquals(MovementMath.DASH, BankPriceMovementPanel.bankStamp(0L, PRICES_AT));
		assertEquals(MovementMath.formatTime(PRICES_AT), BankPriceMovementPanel.bankStamp(PRICES_AT, PRICES_AT));
		assertFalse(BankPriceMovementPanel.bankStamp(PRICES_AT, PRICES_AT + day).contains(":"));

		// ...and the card follows: the same status, painted a day later, dates its bank.
		onEdt(() -> panel.setClock(() -> PRICES_AT + day));
		publish(rows(1), listedWith(summary()));
		final String footnote = panel.footnoteLabel().getText();
		assertTrue(footnote, footnote.startsWith("1d vs 07 Sep - bank "));
		assertFalse(footnote, footnote.contains(":"));
		assertFalse("and it still fits the card", footnote.endsWith(Widgets.ELLIPSIS));
	}

	/**
	 * AF (N 3.1's target, rebuilt): the hover on the card and every child of it is the bank value to the gp and
	 * NOTHING else. The user, looking at the eight-line block it used to be: "it clutters the screen and the
	 * button right under it allow you to see those values" - so what the hover is for now is the one thing the
	 * 28 px figure cannot say, which is the gp the rounding took away.
	 *
	 * <p>The figure and the hover are pinned against the SAME summary field here (playbook 7.5), because the whole
	 * promise is that they are two spellings of one number: a change that rounds one without the other, or that
	 * moves either off {@code valueNow()}, turns this red.
	 */
	@Test
	public void heroTooltipIsTheExactBankValueOnEveryChildOfTheCard() throws Exception
	{
		// T8: this test pins the guide-only card, so it names the switch it is drawing rather than
		// inheriting today's default.
		prefs.storedOptions = LIVE_OFF;
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		final String tip = panel.hero().getToolTipText();
		assertEquals("the gp figure, with thousands separators and its unit", "1,234,567,890 gp", tip);
		assertEquals(VALUE_TIP, tip);
		assertFalse("one line, so no HTML wrapper round it", tip.contains("<html>"));
		assertFalse(tip, tip.contains("<br>"));
		assertEquals("and the card prints the rounded form of that very number",
			MovementMath.formatGp(summary().valueNow()), panel.totalLabel().getText());
		assertEquals("1.23b", panel.totalLabel().getText());

		// Nothing the old block carried survives: the reader gets it from the card or from the controls under it.
		assertFalse("the stack counts are the band button's business now", tip.contains("stacks"));
		assertFalse("a window's own figures are on the move line and the chips", tip.contains("1d"));
		assertFalse(tip, tip.contains("Sums count"));
		assertFalse("the status sentence is the footnote's", tip.contains(STATUS_TEXT));

		assertEquals(tip, BankPriceMovementPanel.valueTooltip(summary(), HeroVisibility.ALL, null));
		assertEquals("a null visibility reads as every figure shown", tip,
			BankPriceMovementPanel.valueTooltip(summary(), null, null));
		for (JComponent c : new JComponent[]{panel.hero(), panel.captionRow(), panel.captionLabel(), panel.totalLabel(),
			panel.moveLine(), panel.triangleLabel(), panel.deltaLabel(), panel.pctLabel(), panel.stripHolder(), panel.footnoteLabel()})
		{
			assertEquals(c.getClass().getSimpleName(), tip, c.getToolTipText());
		}
		assertEquals("the link keeps its own tooltip", BankPriceMovementPanel.REFRESH_TIP, panel.refreshLabel().getToolTipText());
		assertEquals("and the update line keeps its own (S2)", UPDATE_TIP, panel.updateLabel().getToolTipText());
		assertTrue("a chip keeps its own tooltip", panel.windowChip(MovementWindow.D1).getToolTipText().startsWith("Guide-price change"));

		// The lit window is no longer anything to the hover: the figure is the whole bank, whichever chip is on.
		onEdt(() -> panel.selectWindow(MovementWindow.D180));
		assertEquals("a window with no baseline leaves the total - and so the hover - alone", tip,
			panel.hero().getToolTipText());
	}

	/**
	 * O3 / O6: all eight combinations of the three switches. For each: the lines in the card and the figures on
	 * the move line are exactly the ones switched on; the card's height is the full card less the lines removed
	 * (nothing hidden takes its place - {@code DynamicGridLayout.java:211-216}: insets plus the rows' heights);
	 * the width rule holds with the widest figures (N6); and the hover is the exact total while the total is
	 * drawn and no hover at all when it is not (AF) - a hover explains a number, so it goes with the number.
	 */
	@Test
	public void everyCombinationOfTheSwitchesDrawsTheRightLinesFitsTheCardAndTellsTheTooltip() throws Exception
	{
		// T8: this test pins the guide-only card, so it names the switch it is drawing rather than
		// inheriting today's default.
		prefs.storedOptions = LIVE_OFF;
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		final int[] measured = new int[4];
		onEdt(() ->
		{
			measured[0] = panel.hero().getPreferredSize().height;
			measured[1] = panel.totalRow().getPreferredSize().height;
			measured[2] = panel.moveLine().getPreferredSize().height;
			assertTrue("the total line is taller than a hairline: " + measured[1], measured[1] > 20);
			assertTrue("the move line is taller than a hairline: " + measured[2], measured[2] > 12);
			// Q1: hiding the total costs the 28 px FIGURE and not the line - the gear stays on it - so what the
			// card loses is measured by taking the figure away rather than assumed to be the whole row.
			panel.applyHeroVisibility(HeroVisibility.ALL.withValue(false));
			measured[3] = panel.totalRow().getPreferredSize().height;
			panel.applyHeroVisibility(HeroVisibility.ALL);
			assertTrue("the line stays for the gear: " + measured[3], measured[3] > 0 && measured[3] < measured[1]);
		});
		final int fullHeight = measured[0];
		final int totalHeight = measured[1] - measured[3];
		final int moveHeight = measured[2];
		final StringBuilder report = new StringBuilder("hero card in the eight combinations (full " + fullHeight + " px):");

		for (int i = 0; i < 8; i++)
		{
			final HeroVisibility v = combo(i);
			onEdt(() -> panel.applyHeroVisibility(v));
			onEdt(() ->
			{
				assertEquals(v, panel.heroVisibility());
				// The lines.
				final List<Component> lines = new ArrayList<>();
				lines.add(panel.captionRow());
				lines.add(panel.totalRow());
				if (v.moveLine())
				{
					lines.add(panel.moveLine());
				}
				lines.add(panel.stripHolder());
				lines.add(panel.footnoteLabel());
				// S1: the update line is in every one of the eight, last of all.
				lines.add(panel.updateLabel());
				assertEquals(v.toString(), lines, Arrays.asList(panel.hero().getComponents()));
				assertEquals(v.value(), panel.shows(panel.totalLabel()));
				assertTrue(v + ": the gear is drawn whatever the switches say (Q1)", panel.shows(panel.gearLabel()));
				assertEquals(v.moveLine(), panel.shows(panel.moveLine()));
				assertEquals(v.gp(), panel.shows(panel.deltaLabel()));
				assertEquals(v.pct(), panel.shows(panel.pctLabel()));
				final List<Component> parts = new ArrayList<>();
				parts.add(panel.triangleLabel());
				if (v.gp())
				{
					parts.add(panel.deltaLabel());
				}
				if (v.pct())
				{
					parts.add(panel.pctLabel());
				}
				assertEquals(v + ": the move line's parts", parts, Arrays.asList(panel.moveLine().getComponents()));
				assertEquals("the edge is a direction hint, not a figure, and stays", ColorScheme.PROGRESS_COMPLETE_COLOR.darker(), edgeOf(panel.hero()));
				assertEquals("the menu ticks follow", v.value(), panel.showValueItem().isSelected());
				assertEquals(v.gp(), panel.showGpItem().isSelected());
				assertEquals(v.pct(), panel.showPctItem().isSelected());

				// The height: the full card less exactly the lines that went.
				final int expected = fullHeight - (v.value() ? 0 : totalHeight) - (v.moveLine() ? 0 : moveHeight);
				final int height = panel.hero().getPreferredSize().height;
				final Insets in = panel.hero().getInsets();
				int sum = in.top + in.bottom;
				for (Component line : panel.hero().getComponents())
				{
					sum += line.getPreferredSize().height;
				}
				report.append("\n  ").append(v).append(" -> ").append(height).append(" px");
				assertEquals(v + ": the card is the full card less the lines removed", expected, height);
				assertEquals(v + ": the card is exactly its lines", sum, height);

				// The hover (AF): the exact total while the total is drawn, and nothing at all when it is not.
				// It follows v.value() ALONE - the gp and percent switches decide what is on the move line, and
				// the move line's figures were never what this hover explained.
				final String tip = panel.hero().getToolTipText();
				assertEquals(v.toString(), v.value() ? VALUE_TIP : null, tip);
				assertEquals(v.toString(), v.value() ? VALUE_TIP : "",
					BankPriceMovementPanel.valueTooltip(summary(), v, null));
				for (JComponent c : new JComponent[]{panel.captionRow(), panel.captionLabel(), panel.totalRow(),
					panel.totalLabel(), panel.moveLine(), panel.deltaLabel(), panel.pctLabel(), panel.stripHolder(),
					panel.footnoteLabel()})
				{
					assertEquals(v + ": " + c.getClass().getSimpleName(), tip, c.getToolTipText());
				}
				assertEquals(v + ": the gear keeps its own word", BankPriceMovementPanel.OPTIONS_TIP,
					panel.gearLabel().getToolTipText());
				assertEquals(v + ": the update line keeps its own hover (S2)", UPDATE_TIP,
					panel.updateLabel().getToolTipText());
				assertTrue(panel.describe(), panel.describe().contains("\"hero\":{\"value\":" + v.value() + ",\"gp\":" + v.gp() + ",\"pct\":" + v.pct() + "}"));
			});

			// The width rule (N6 / O6) with the widest figures, in this combination.
			publish(rows(3), listedWith(hugeSummary()));
			onEdt(() ->
			{
				final int inner = BankPriceMovementPanel.CARD_INNER;
				for (Component line : panel.hero().getComponents())
				{
					final int lw = line.getPreferredSize().width;
					assertTrue(v + ": a hero line asks for " + lw + " px, more than the card's " + inner, lw <= inner);
				}
				assertTrue(v + ": the card asks for " + panel.hero().getPreferredSize().width, panel.hero().getPreferredSize().width <= W);
				assertTrue(Widgets.widest(panel.header()) <= W);
				assertFalse("the total is whole", panel.totalLabel().getText().endsWith(Widgets.ELLIPSIS));
				assertEquals("-99.9%", panel.pctLabel().getText());
				assertFalse(panel.deltaLabel().getText().endsWith(Widgets.ELLIPSIS));
				// The widest total there is: the hover spells all twelve digits, however the card had to fit it.
				assertEquals(v.toString(), v.value() ? "999,999,999,999 gp" : null, panel.hero().getToolTipText());
			});
			publish(rows(3), listedWith(summary()));
		}
		System.out.println(report);
		assertTrue("nothing was written: applyHeroVisibility is the config's own path", prefs.heroSaves.isEmpty());
		assertTrue(prefs.saves.isEmpty());
		verify(service, never()).setFilter(any());
	}

	/** O3: with everything hidden the card is its caption, its chips and its footnote - and the bridge still sees the figures. */
	@Test
	public void hidingEveryFigureLeavesTheCaptionTheChipsTheFootnoteAndTheEdge() throws Exception
	{
		// T8: this test pins the guide-only card, so it names the switch it is drawing rather than
		// inheriting today's default.
		prefs.storedOptions = LIVE_OFF;
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		onEdt(() -> panel.applyHeroVisibility(HeroVisibility.NONE));
		onEdt(() ->
		{
			assertEquals("the total's line stays, with the gear alone on it (Q1); the update line stays too (S1)",
				Arrays.asList(panel.captionRow(), panel.totalRow(), panel.stripHolder(), panel.footnoteLabel(),
					panel.updateLabel()),
				Arrays.asList(panel.hero().getComponents()));
			assertEquals(Collections.singletonList(panel.gearLabel()), Arrays.asList(panel.totalRow().getComponents()));
			assertEquals(BankPriceMovementPanel.VALUE_TITLE, panel.captionLabel().getText());
			assertEquals(BankPriceMovementPanel.REFRESH_TEXT, panel.refreshLabel().getText());
			assertTrue(Widgets.isLit(panel.windowChip(MovementWindow.D1)));
			assertTrue(panel.footnoteLabel().getText(), panel.footnoteLabel().getText().startsWith("1d vs 07 Sep - bank "));
			assertEquals("the edge stays", ColorScheme.PROGRESS_COMPLETE_COLOR.darker(), edgeOf(panel.hero()));
			assertNull("AF: no figure under the pointer, so no hover to explain one", panel.hero().getToolTipText());
			assertEquals(BankPriceMovementPanel.UPDATE_TEXT, panel.updateLabel().getText());
			final String d = panel.describe();
			assertTrue(d, d.contains("\"hero\":{\"value\":false,\"gp\":false,\"pct\":false},\"bankValueShowing\":true,\"bankValue\":\"\",\"bankMove\":\"1d   +12.4m   +1.0%\",\"bankWindow\":\"1d\",\"heroGp\":\"\",\"heroPct\":\"\",\"heroSub\":\"1d vs 07 Sep - bank "));

			// A window with no baseline while everything is hidden: still nothing on the card but the four.
			panel.selectWindow(MovementWindow.D180);
			assertEquals(5, panel.hero().getComponentCount());
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, edgeOf(panel.hero()));
			assertTrue(panel.footnoteLabel().getText().startsWith("Guide prices - bank "));
		});
		// Nothing ever fetched and everything hidden: no tooltip at all rather than an empty box - and the update
		// line still carries its own, because its first two sentences are true before any fetch (S2).
		final PriceService.Status bare = status(true, true, 3, 3, MovementWindow.D1, null, "", 0L);
		when(bare.headerText()).thenReturn("");
		publish(rows(3), bare);
		assertNull(panel.hero().getToolTipText());
		assertNull(panel.footnoteLabel().getToolTipText());
		assertEquals(BankPriceMovementPanel.updateTooltip(0L), panel.updateLabel().getToolTipText());

		// ...and once prices have been read, the card says no more than it did - the clock is the update line's
		// business (S3), and the hover is the total's, which is hidden.
		final PriceService.Status fetched = status(true, true, 3, 3, MovementWindow.D1, null, "", PRICES_AT);
		when(fetched.headerText()).thenReturn("");
		publish(rows(3), fetched);
		assertNull("S3: the card has nothing left to say when every figure is hidden", panel.hero().getToolTipText());
		assertEquals(UPDATE_TIP, panel.updateLabel().getToolTipText());
	}

	/** N 4.6 under O3: a window with no baseline prints ONE dash on the move line, whichever figure carries it. */
	@Test
	public void aMissingBaselineDrawsOneDashWhicheverFigureIsShown() throws Exception
	{
		build();
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			panel.selectWindow(MovementWindow.D180);
			assertEquals(MovementMath.DASH, panel.deltaLabel().getText());
			assertEquals("", panel.pctLabel().getText());
			assertNull(panel.triangleLabel().getIcon());

			panel.applyHeroVisibility(HeroVisibility.ALL.withGp(false));
			assertEquals("the percent carries the dash while the gp is hidden", MovementMath.DASH, panel.pctLabel().getText());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.pctLabel().getForeground());
			assertEquals(Arrays.asList(panel.triangleLabel(), panel.pctLabel()), Arrays.asList(panel.moveLine().getComponents()));

			panel.applyHeroVisibility(HeroVisibility.ALL.withPct(false));
			assertEquals(MovementMath.DASH, panel.deltaLabel().getText());
			assertEquals(Arrays.asList(panel.triangleLabel(), panel.deltaLabel()), Arrays.asList(panel.moveLine().getComponents()));

			panel.applyHeroVisibility(HeroVisibility.ALL);
			panel.selectWindow(MovementWindow.D1);
			assertEquals("+12.4m", panel.deltaLabel().getText());
			assertEquals("+1.0%", panel.pctLabel().getText());
		});
	}

	@Test
	public void bankValueTextsAreThePureHelpers()
	{
		final PortfolioSummary s = summary();
		assertEquals("1d   +12.4m   +1.0%", BankPriceMovementPanel.moveText(MovementWindow.D1, s.move(MovementWindow.D1)));
		assertEquals("30d   -3.1m   -0.2%", BankPriceMovementPanel.moveText(MovementWindow.D30, s.move(MovementWindow.D30)));
		assertEquals("90d   -   -", BankPriceMovementPanel.moveText(MovementWindow.D90, null));
		assertEquals("null window reads as the default", "1d   -   -", BankPriceMovementPanel.moveText(null, null));
		assertEquals("a window whose covered stacks were worth nothing then: a gp move and no percent", "1d   0   -",
			BankPriceMovementPanel.moveText(MovementWindow.D1, new WindowMove(MovementWindow.D1, THEN_DAY, 0L, 0L, 0L, null, 0)));
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, BankPriceMovementPanel.moveColor(s.move(MovementWindow.D1)));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, BankPriceMovementPanel.moveColor(s.move(MovementWindow.D30)));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, BankPriceMovementPanel.moveColor(s.move(MovementWindow.D7)));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, BankPriceMovementPanel.moveColor(null));
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR.darker(), BankPriceMovementPanel.edgeColor(s.move(MovementWindow.D1)));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(), BankPriceMovementPanel.edgeColor(s.move(MovementWindow.D30)));
		assertEquals(ColorScheme.DARKER_GRAY_COLOR, BankPriceMovementPanel.edgeColor(s.move(MovementWindow.D7)));
		assertEquals(ColorScheme.DARKER_GRAY_COLOR, BankPriceMovementPanel.edgeColor(null));
		// L2 on the sum: a fall too small to survive truncation still reads "-0.0%" and paints red.
		final WindowMove tiny = new WindowMove(MovementWindow.D1, THEN_DAY, 1_000_000_000L, 999_999_990L, -10L, -0.000001d, 3);
		assertEquals("1d   -10   -0.0%", BankPriceMovementPanel.moveText(MovementWindow.D1, tiny));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, BankPriceMovementPanel.moveColor(tiny));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(), BankPriceMovementPanel.edgeColor(tiny));
	}

	/**
	 * The percentage the card PRINTS, read off the card itself: a dash without a baseline, a dash for a window
	 * that has one but no percentage to give (nothing was worth anything on the baseline day), and L2's signed
	 * figure otherwise - a fall too small to survive truncation keeps its minus and its red.
	 *
	 * <p>It used to be asserted against a static helper that formatted the same figure a second time, which
	 * proved only that the two copies agreed; this asks the label after a publish, which is what a reader sees.
	 */
	@Test
	public void theCardsPercentIsADashWithoutOneAndTheSignedFigureWithIt() throws Exception
	{
		build();
		final PortfolioSummary s = summary();
		publish(rows(3), listedWith(s));
		assertEquals("+1.0%", panel.pctLabel().getText());

		onEdt(() -> panel.selectWindow(MovementWindow.D90));
		assertEquals("no baseline for 90d", MovementMath.DASH, panel.deltaLabel().getText());
		assertEquals("", panel.pctLabel().getText());

		// A window WITH a baseline whose covered stacks were worth nothing then: a gp move and no percentage.
		final Map<MovementWindow, WindowMove> noPct = new EnumMap<>(MovementWindow.class);
		noPct.put(MovementWindow.D1, new WindowMove(MovementWindow.D1, THEN_DAY, 0L, 0L, 5L, null, 0));
		publish(rows(3), listedWith(new PortfolioSummary(5L, 1, 1, noPct)));
		onEdt(() -> panel.selectWindow(MovementWindow.D1));
		assertEquals(MovementMath.DASH, panel.pctLabel().getText());

		final Map<MovementWindow, WindowMove> tiny = new EnumMap<>(MovementWindow.class);
		tiny.put(MovementWindow.D1, new WindowMove(MovementWindow.D1, THEN_DAY, 1_000_000_000L, 999_999_990L, -10L,
			-0.000001d, 3));
		publish(rows(3), listedWith(new PortfolioSummary(999_999_990L, 3, 3, tiny)));
		assertEquals("-0.0%", panel.pctLabel().getText());
		assertEquals(Widgets.MOVE_DOWN_TEXT, panel.pctLabel().getForeground());
	}

	/**
	 * AF's hover as a pure function: {@code MovementMath.formatExact(valueNow())} and nothing else, plain text
	 * while there is one line to say, {@code ""} while the total is hidden, and the degraded sentence on a second
	 * line under the figure when there is one - which is the only thing that ever puts HTML round it.
	 */
	@Test
	public void valueTooltipIsTheExactBankValueAndNothingElse()
	{
		final PortfolioSummary s = summary();
		assertEquals("1,234,567,890 gp", BankPriceMovementPanel.valueTooltip(s, HeroVisibility.ALL, null));
		assertEquals("the switches below the total are nothing to it: only value() decides",
			"1,234,567,890 gp", BankPriceMovementPanel.valueTooltip(s, HeroVisibility.of(true, false, false), null));
		assertEquals("a null visibility reads as every figure shown", "1,234,567,890 gp",
			BankPriceMovementPanel.valueTooltip(s, null, null));
		assertEquals("an empty warning is no warning", "1,234,567,890 gp",
			BankPriceMovementPanel.valueTooltip(s, HeroVisibility.ALL, ""));

		// Hidden: "", which is the caller's signal to set no tooltip at all rather than an empty yellow box.
		assertEquals("", BankPriceMovementPanel.valueTooltip(s, HeroVisibility.NONE, null));
		assertEquals("hidden beats a warning - there is no figure for it to hang under", "",
			BankPriceMovementPanel.valueTooltip(s, HeroVisibility.NONE, UNAVAILABLE));
		assertEquals("", BankPriceMovementPanel.valueTooltip(s, HeroVisibility.of(false, true, true), null));

		// Degraded: the figure, a break, the sentence - and the sentence is escaped, because the service composes
		// it from the wiki's own words and a stray angle bracket would eat the rest of the tooltip.
		assertEquals("<html>1,234,567,890 gp<br>" + UNAVAILABLE + "</html>",
			BankPriceMovementPanel.valueTooltip(s, HeroVisibility.ALL, UNAVAILABLE));
		assertEquals("<html>1,234,567,890 gp<br>a &lt;b&gt; &amp; c</html>",
			BankPriceMovementPanel.valueTooltip(s, HeroVisibility.ALL, "a <b> & c"));

		// A bank worth nothing still answers a figure: "0 gp" is the truth, and a blank hover would read as a bug.
		assertEquals("0 gp", BankPriceMovementPanel.valueTooltip(PortfolioSummary.EMPTY, HeroVisibility.ALL, null));
		assertEquals("0 gp", BankPriceMovementPanel.valueTooltip(
			new PortfolioSummary(0L, 0, 0, Collections.emptyMap()), HeroVisibility.ALL, null));
		assertEquals("<html>0 gp<br>" + UNAVAILABLE + "</html>",
			BankPriceMovementPanel.valueTooltip(PortfolioSummary.EMPTY, HeroVisibility.ALL, UNAVAILABLE));
	}

	/**
	 * P1: coins and platinum tokens are half of what a player means by "bank value", so they are IN the 28 px
	 * total - and, since AF, in the hover under it to the gp, because a reader adding the rows up by hand would
	 * otherwise be short by exactly the cash and have nothing to check their arithmetic against.
	 */
	@Test
	public void theCardCountsCoinsInTheTotalAndInItsExactHover() throws Exception
	{
		// T8: this test pins the guide-only card, so it names the switch it is drawing rather than
		// inheriting today's default.
		prefs.storedOptions = LIVE_OFF;
		buildWithHovers();
		publish(rows(3), listedWith(summaryWithCash(791_078L)));
		assertEquals("the stacks plus the cash, to the gp", "1,235,358,968 gp", panel.hero().getToolTipText());
		assertEquals("1.23b", panel.totalLabel().getText());

		// The same bank with no cash: the hover is short by exactly the 791,078 gp that left it.
		publish(rows(3), listedWith(summary()));
		assertEquals("1,234,567,890 gp", panel.hero().getToolTipText());
		assertEquals("and the difference is the cash", 791_078L,
			summaryWithCash(791_078L).valueNow() - summary().valueNow());

		// The 28 px figure is the whole bank, cash included - here a bank that is nothing but cash.
		publish(rows(3), listedWith(new PortfolioSummary(0L, 0, 0, Collections.emptyMap(), 200_000_000L)));
		onEdt(() ->
		{
			assertEquals("200m", panel.totalLabel().getText());
			assertEquals("200,000,000 gp", panel.hero().getToolTipText());
		});
		publish(rows(3), listedWith(summaryWithCash(791_078L)));
		onEdt(() -> assertEquals("the stacks plus the cash, in stack form", "1.23b", panel.totalLabel().getText()));
	}

	/**
	 * S1: the card's last line. The user asked for "some written indication that it gets data only once every 24
	 * if the user is refreshing", in their own words - so the line is drawn permanently, in the footnote's own
	 * face and grey, directly under it and before the card's bottom padding, and the card grows by exactly that
	 * one line and nothing else moves.
	 */
	@Test
	public void theCardCarriesTheUpdateLineUnderTheFootnote() throws Exception
	{
		// T8: this test pins the guide-only card, so it names the switch it is drawing rather than
		// inheriting today's default.
		prefs.storedOptions = LIVE_OFF;
		build();
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			final JLabel line = panel.updateLabel();
			assertEquals("the user's own wording, pinned", "Item prices update every 24hrs", BankPriceMovementPanel.UPDATE_TEXT);
			assertEquals(BankPriceMovementPanel.UPDATE_TEXT, line.getText());
			assertEquals("the footnote's face", panel.footnoteLabel().getFont(), line.getFont());
			assertEquals("the footnote's grey", ColorScheme.LIGHT_GRAY_COLOR, line.getForeground());
			// Left-aligned, the way the footnote is: the label's own LEADING default, not the centring the
			// "Show n more" row asks for.
			assertEquals(SwingConstants.LEADING, line.getHorizontalAlignment());
			assertEquals(panel.footnoteLabel().getHorizontalAlignment(), line.getHorizontalAlignment());
			final Component[] lines = panel.hero().getComponents();
			assertSame("last of all, under the footnote", line, lines[lines.length - 1]);
			assertSame(panel.footnoteLabel(), lines[lines.length - 2]);
			assertFalse("it is never cut", line.getText().endsWith(Widgets.ELLIPSIS));
			assertTrue("and it fits the card: " + line.getPreferredSize().width,
				line.getPreferredSize().width <= BankPriceMovementPanel.CARD_INNER);

			// The card is exactly its lines, so the growth IS the line - nothing else moved (S1).
			int sum = panel.hero().getInsets().top + panel.hero().getInsets().bottom;
			for (Component c : lines)
			{
				sum += c.getPreferredSize().height;
			}
			assertEquals(sum, panel.hero().getPreferredSize().height);
			System.out.println("the update line: " + line.getPreferredSize().width + " x "
				+ line.getPreferredSize().height + " px of " + BankPriceMovementPanel.CARD_INNER);

			// S4: a script can read it without a picture.
			assertTrue(panel.describe(), panel.describe().contains("\"updateLine\":\"Item prices update every 24hrs\","));
		});

		// Every state keeps it: no baseline, logged out, a degraded status, a bank that is nothing but cash.
		onEdt(() -> panel.selectWindow(MovementWindow.D180));
		assertEquals(BankPriceMovementPanel.UPDATE_TEXT, panel.updateLabel().getText());
		publish(rows(3), status(false, true, 3, 3, MovementWindow.D1, null, STATUS_TEXT, PRICES_AT));
		assertTrue(panel.shows(panel.updateLabel()));
		assertEquals(BankPriceMovementPanel.UPDATE_TEXT, panel.updateLabel().getText());
	}

	/**
	 * S2: the hover behind that line - why the figures sit still, and when this client last looked. Three plain
	 * sentences in the card's own tooltip HTML; the clock is {@code Status.pricesAtMillis()} through
	 * {@link MovementMath#formatTime}, the path the card's tooltip printed it by until S3, and its whole sentence
	 * is absent while nothing was ever fetched rather than stamping a dash where a time belongs.
	 */
	@Test
	public void theUpdateLinesHoverSaysWhyTheDataStepsOnceADayAndWhenItWasLastChecked() throws Exception
	{
		// The clock is the viewer's own (MovementMath.formatTime reads in the default zone), so it is composed
		// rather than quoted; every other character of the three sentences is pinned.
		assertEquals("the wording addendum S asks for, pinned",
			"<html>2h Bank Portfolio Tracker uses the Grand Exchange guide price, which Jagex publishes once a day at a"
				+ " varying hour.<br>It is the price shown on the Grand Exchange website.<br>RuneLite's own item hover uses the wiki's traded price by default, which differs most on thinly traded items.<br>Jagex moves a guide price by at most about 5% a day, so a large move shows over several days.<br>Refresh re-checks for it, and the plugin re-checks by itself every 30 minutes."
				+ "<br>Last checked " + MovementMath.formatTime(PRICES_AT) + ".</html>",
			BankPriceMovementPanel.updateTooltip(PRICES_AT));
		assertEquals("nothing ever fetched: five sentences, no clock",
			"<html>2h Bank Portfolio Tracker uses the Grand Exchange guide price, which Jagex publishes once a day at a"
				+ " varying hour.<br>It is the price shown on the Grand Exchange website.<br>RuneLite's own item hover uses the wiki's traded price by default, which differs most on thinly traded items.<br>Jagex moves a guide price by at most about 5% a day, so a large move shows over several days.<br>Refresh re-checks for it, and the plugin re-checks by itself every 30 minutes."
				+ "</html>",
			BankPriceMovementPanel.updateTooltip(0L));
		assertEquals("and a clock is never invented", BankPriceMovementPanel.updateTooltip(0L),
			BankPriceMovementPanel.updateTooltip(-1L));
		assertEquals("the half-hour is the service's own tick", 30L * 60L * 1000L, PriceService.TICK_MS);

		// T8: this test pins the guide-only card, so it names the switch it is drawing rather than
		// inheriting today's default.
		prefs.storedOptions = LIVE_OFF;
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		assertEquals(UPDATE_TIP, panel.updateLabel().getToolTipText());
		assertTrue(UPDATE_TIP, UPDATE_TIP.contains(MovementMath.formatTime(PRICES_AT)));
		assertNotEquals("it is the line's own hover, not the card's", panel.hero().getToolTipText(),
			panel.updateLabel().getToolTipText());

		// The clock follows the status, and goes away with it.
		final PriceService.Status fresh = status(true, true, 3, 3, MovementWindow.D1, null, STATUS_TEXT, 0L);
		when(fresh.portfolio()).thenReturn(summary());
		publish(rows(3), fresh);
		assertEquals(BankPriceMovementPanel.updateTooltip(0L), panel.updateLabel().getToolTipText());
		assertFalse(panel.updateLabel().getToolTipText(), panel.updateLabel().getToolTipText().contains("Last checked"));

		// S2: the Refresh link says how often Jagex publishes, which is what decides whether a second tap is worth
		// making - the 30 s cooldown it used to name is answered in the problem row instead.
		assertEquals("the wording addendum S asks for, pinned",
			"Re-check the guide prices. Jagex publishes them once a day.", BankPriceMovementPanel.REFRESH_TIP);
		assertEquals(BankPriceMovementPanel.REFRESH_TIP, panel.refreshLabel().getToolTipText());
	}

	/**
	 * S3, under AF: no clock on the card's hover. Addendum P hung "Guide prices change once a day - last checked
	 * 09:05" on the end of it, S3 moved that to the update line's own hover, and AF took the rest away - so the
	 * only thing that can follow the figure now is the degraded sentence, and it is followed by nothing.
	 *
	 * <p>The warning survives AF because it is the one thing the card cannot repeat: the footnote reddens for it
	 * but is fitted to 191 px and cannot carry the sentence (B005 / B101).
	 */
	@Test
	public void theCardHoverCarriesNoClockAndEndsOnTheDegradedSentence() throws Exception
	{
		// T8: this test pins the guide-only card, so it names the switch it is drawing rather than
		// inheriting today's default.
		prefs.storedOptions = LIVE_OFF;
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		final String tip = panel.hero().getToolTipText();
		assertEquals(VALUE_TIP, tip);
		assertFalse(tip, tip.contains("last checked"));
		assertFalse(tip, tip.contains(MovementMath.formatTime(PRICES_AT)));

		// A degraded status: the figure, a break, the sentence, and the tooltip ends there.
		final PriceService.Status blind = listedWith(summary());
		when(blind.degraded()).thenReturn(true);
		when(blind.degradedReason()).thenReturn(UNAVAILABLE);
		publish(rows(3), blind);
		assertEquals("<html>" + VALUE_TIP + "<br>" + UNAVAILABLE + "</html>", panel.hero().getToolTipText());
		assertFalse(panel.hero().getToolTipText(), panel.hero().getToolTipText().contains("last checked"));
		assertFalse("the status sentence is the footnote's, degraded or not",
			panel.hero().getToolTipText().contains(STATUS_TEXT));

		// ...and the clock it used to print is on the update line's hover, off the same status field.
		assertEquals(UPDATE_TIP, panel.updateLabel().getToolTipText());

		// A confident status takes the second line back off, and the hover is the bare figure again.
		publish(rows(3), listedWith(summary()));
		assertEquals(VALUE_TIP, panel.hero().getToolTipText());
	}

	/**
	 * T1: "Live prices" leads the gear menu's last group - the price series is answered before what is counted and
	 * how it is drawn - carries its config item's own description, is ticked by default (the switch is ON out of
	 * the box), and writes through {@code saveOptions} the way addendum Q's three do. The ROWS are not rebuilt for
	 * it: which stacks are live is the service's answer, and it arrives as an ordinary publish.
	 */
	@Test
	public void theLivePricesItemLeadsTheViewGroupAndWritesThePref() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		// Y4 gave it a verb: the switch addendum T named "Live prices" reads "Use live prices" in the menu and on
		// the settings page, and nothing else about it moved.
		assertEquals("the name addendum Y asks for, pinned", "Use live prices", BankPriceMovementPanel.LIVE_PRICES_TEXT);
		assertEquals("the description addendum T asks for, pinned",
			"Actively traded items use the wiki's live traded prices for every figure; thin items keep the daily"
				+ " guide price", BankPriceMovementPanel.LIVE_PRICES_TIP);
		assertEquals(BankPriceMovementPanel.LIVE_PRICES_TIP, panel.livePricesItem().getToolTipText());
		assertSame(panel.livePricesItem(), item(panel.heroMenu(), BankPriceMovementPanel.LIVE_PRICES_TEXT));
		assertTrue("live prices are on by default (T1)", panel.livePricesItem().isSelected());
		assertTrue(panel.options().livePrices());
		assertEquals("construction saves nothing", Collections.emptyList(), prefs.optionSaves);

		final int rebuilds = panel.rebuilds();
		onEdt(() -> panel.livePricesItem().doClick(0));
		// hovers(...) throughout, because this panel was built with AH's hover switch on - the gear item's own
		// tooltip is read above, and since AH3 there is none to read with the switch off. It rides along in
		// every ViewOptions here and moves nothing else.
		assertEquals(hovers(LIVE_OFF), panel.options());
		assertFalse("the tick follows the click", panel.livePricesItem().isSelected());
		assertEquals("the pref is written so the config panel follows", Arrays.asList(hovers(LIVE_OFF)),
			prefs.optionSaves);
		assertEquals("the card follows at once", BankPriceMovementPanel.UPDATE_TEXT, panel.updateLabel().getText());
		assertEquals("the rows wait for the service's own recompute", rebuilds, panel.rebuilds());
		assertTrue("the filter and the hero switches are untouched", prefs.saves.isEmpty());
		assertTrue(prefs.heroSaves.isEmpty());
		verify(service, never()).setFilter(any());

		// The config's own road back ticks and repaints and writes nothing more.
		onEdt(() -> panel.applyOptions(hovers(ViewOptions.DEFAULT)));
		assertTrue(panel.livePricesItem().isSelected());
		assertEquals(BankPriceMovementPanel.UPDATE_LIVE_TEXT, panel.updateLabel().getText());
		assertEquals(1, prefs.optionSaves.size());
	}

	/**
	 * T5: the card's last line has two texts and two hovers, and which it draws is the SWITCH and not the data -
	 * it is the answer to "why might this figure move when I refresh?", which is true before the first fetch. With
	 * the switch off it is addendum S's line and addendum S's hover, word for word (T8).
	 */
	@Test
	public void theCardSaysLivePricesAreOnWhileTheSwitchIsOn() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			assertEquals("the wording addendum T asks for, pinned", "Live prices on - thin items daily",
				BankPriceMovementPanel.UPDATE_LIVE_TEXT);
			assertEquals(BankPriceMovementPanel.UPDATE_LIVE_TEXT, panel.updateLabel().getText());
			// S1 still holds of the line itself: the footnote's face and grey, last of all, never cut.
			assertEquals(panel.footnoteLabel().getFont(), panel.updateLabel().getFont());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.updateLabel().getForeground());
			final Component[] lines = panel.hero().getComponents();
			assertSame(panel.updateLabel(), lines[lines.length - 1]);
			assertFalse("it is never cut", panel.updateLabel().getText().endsWith(Widgets.ELLIPSIS));
			assertTrue("and it fits the card: " + panel.updateLabel().getPreferredSize().width,
				panel.updateLabel().getPreferredSize().width <= BankPriceMovementPanel.CARD_INNER);

			final String tip = panel.updateLabel().getToolTipText();
			// V5 rewrote the second sentence: five checks now, folded two by two into the clauses that were
			// already there ("today or yesterday", "the guide or from yesterday's average").
			assertEquals("the hover addenda T and V ask for, pinned",
				"<html>Actively traded items show the wiki's live traded price, refreshed on Refresh and every 30"
					+ " minutes; their windows compare against that day's traded average."
					+ "<br>Thin items (fewer than 100 traded yesterday, a wide buy/sell gap today or yesterday, or a"
					+ " live price more than 50 % from the guide or from yesterday's average) keep the daily Grand"
					+ " Exchange guide price."
					+ "<br>Last checked " + MovementMath.formatTime(PRICES_AT) + ".</html>", tip);
			assertFalse("the GE-site sentence is true of the guide series and not of this one",
				tip.contains("Grand Exchange website"));
			assertFalse("nothing fetched yet: no clock invented",
				BankPriceMovementPanel.updateTooltip(0L, ViewOptions.DEFAULT).contains("Last checked"));
			assertNotEquals("it is the line's own hover, not the card's", panel.hero().getToolTipText(), tip);

			// T8: off is addendum S's line and hover, to the character. The hovers stay ON across the flip, so
			// the only switch moving here is the live one - AH3 put every tooltip behind the other.
			panel.applyOptions(hovers(LIVE_OFF));
			assertEquals(BankPriceMovementPanel.UPDATE_TEXT, panel.updateLabel().getText());
			assertEquals(UPDATE_TIP, panel.updateLabel().getToolTipText());
			assertEquals(BankPriceMovementPanel.updateTooltip(PRICES_AT), panel.updateLabel().getToolTipText());

			// ...and back, without a publish: the line is the panel's own switch.
			panel.applyOptions(hovers(ViewOptions.DEFAULT));
			assertEquals(BankPriceMovementPanel.UPDATE_LIVE_TEXT, panel.updateLabel().getText());
			assertEquals("null reads as the defaults (T1)", panel.updateLabel().getText(),
				BankPriceMovementPanel.updateText(null));
			assertEquals(BankPriceMovementPanel.UPDATE_TEXT, BankPriceMovementPanel.updateText(LIVE_OFF));
		});

		// The line is drawn before any fetch and with the wiki down, exactly as it is after one (T5).
		publish(rows(3), status(true, true, 3, 3, MovementWindow.D1, null, STATUS_TEXT, 0L));
		assertEquals(BankPriceMovementPanel.UPDATE_LIVE_TEXT, panel.updateLabel().getText());
		assertFalse(panel.updateLabel().getToolTipText(), panel.updateLabel().getToolTipText().contains("Last checked"));
		assertTrue(panel.describe(), panel.describe().contains(
			"\"updateLine\":\"Live prices on - thin items daily\","));
	}

	/**
	 * T8, under AF: the live switch changes the card's update LINE and the footnote, and it no longer changes the
	 * hover at all - the hover is the total, and the total is the service's answer under whichever switch is on.
	 *
	 * <p>Addendum T's own count ("123 of 519 stacks live") went with the eight-line tooltip: it is the dev
	 * bridge's {@code state.live.liveRows} now and is drawn nowhere.
	 */
	@Test
	public void theLiveSwitchLeavesTheCardsHoverAlone() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listedWithLive(summaryWithLive(123), 123, LocalDate.of(2026, 9, 9), null));
		assertEquals(VALUE_TIP, panel.hero().getToolTipText());
		assertEquals("the switch is drawn on the line under the figure, not in the hover over it",
			BankPriceMovementPanel.UPDATE_LIVE_TEXT, panel.updateLabel().getText());
		assertTrue("and the count is still readable from a terminal",
			panel.describe().contains("\"liveRows\":123"));

		// AH: the hover switch rides along, because applyOptions takes the WHOLE value - turning the live series
		// off with a bare LIVE_OFF here would turn the hovers off with it and leave nothing to read.
		onEdt(() -> panel.applyOptions(hovers(LIVE_OFF)));
		assertEquals("the same bank, live or not: the hover is the total", VALUE_TIP, panel.hero().getToolTipText());
		assertEquals(BankPriceMovementPanel.UPDATE_TEXT, panel.updateLabel().getText());
	}

	// ---- addendum U: the live calendar on the card (U3)

	/**
	 * U3: while live prices are on AND this publish put rows on the traded series, the footnote stamps the LIVE day
	 * for the lit window - the day those rows compared against - and not the guide's baseline day beside it. The two
	 * part company for every hour before Jagex publishes the day's table, which is what made a Partyhat set read
	 * "+30.0 %" for 1 d on the live look of 2026-09-12: the figure was two days wide under a label that said one.
	 *
	 * <p>Everything else keeps the guide day, and each of those states is a way the live day would be a lie: the
	 * switch off, a publish with nothing liquid enough to qualify, a window with no traded bucket, and a status from
	 * before addendum U that carries no calendar at all.
	 */
	@Test
	public void theFootnoteStampsTheLiveDayWhileLiveRowsAreOnScreen() throws Exception
	{
		build();
		final LocalDate liveDay = LocalDate.of(2026, 9, 8);
		final PriceService.Status live = listedWithLive(summary(), 123, LocalDate.of(2026, 9, 9),
			liveDays(MovementWindow.D1, liveDay));
		publish(rows(3), live);
		assertTrue(panel.footnoteLabel().getText(), panel.footnoteLabel().getText().startsWith("1d vs 08 Sep - bank "));
		assertFalse("the guide's own day is not the day those rows used",
			panel.footnoteLabel().getText().contains("07 Sep"));
		assertFalse("and it still fits the card", panel.footnoteLabel().getText().endsWith(Widgets.ELLIPSIS));
		assertTrue(panel.describe(), panel.describe().contains("\"heroSub\":\"1d vs 08 Sep - bank "));

		// The SWITCH, without a publish: off is the guide day at once, on is the live day again - the same beat the
		// update line changes on, so the card can never name a series it is no longer drawing.
		onEdt(() -> panel.applyOptions(LIVE_OFF));
		assertTrue(panel.footnoteLabel().getText(), panel.footnoteLabel().getText().startsWith("1d vs 07 Sep - bank "));
		onEdt(() -> panel.applyOptions(ViewOptions.DEFAULT));
		assertTrue(panel.footnoteLabel().getText(), panel.footnoteLabel().getText().startsWith("1d vs 08 Sep - bank "));

		// The rule itself, in every state that decides it.
		final WindowMove move = summary().move(MovementWindow.D1);
		assertEquals("live rows on screen: the day they compared against", liveDay,
			BankPriceMovementPanel.footnoteDay(live, MovementWindow.D1, move, ViewOptions.DEFAULT));
		assertEquals("a window with no traded bucket keeps the guide day", THEN_DAY.minusDays(6),
			BankPriceMovementPanel.footnoteDay(live, MovementWindow.D7, summary().move(MovementWindow.D7),
				ViewOptions.DEFAULT));
		assertEquals("the switch off keeps the guide day", THEN_DAY,
			BankPriceMovementPanel.footnoteDay(live, MovementWindow.D1, move, LIVE_OFF));
		assertEquals("no live rows: nothing on screen used a traded day", THEN_DAY,
			BankPriceMovementPanel.footnoteDay(listedWithLive(summary(), 0, LocalDate.of(2026, 9, 9),
				liveDays(MovementWindow.D1, liveDay)), MovementWindow.D1, move, ViewOptions.DEFAULT));
		assertEquals("a status with no live calendar is the pre-U one", THEN_DAY,
			BankPriceMovementPanel.footnoteDay(listedWith(summary()), MovementWindow.D1, move, ViewOptions.DEFAULT));
		assertEquals("null options read as the defaults", liveDay,
			BankPriceMovementPanel.footnoteDay(live, MovementWindow.D1, move, null));
		assertEquals("a null window reads as the lit default", liveDay,
			BankPriceMovementPanel.footnoteDay(live, null, move, ViewOptions.DEFAULT));
		assertNull("no move, no day: the footnote reads \"Guide prices\"",
			BankPriceMovementPanel.footnoteDay(live, MovementWindow.D1, null, ViewOptions.DEFAULT));
		final PriceService.Status.LiveStatus calendar = live.live();
		final PriceService.Status out = statusWith(false, summary());
		when(out.live()).thenReturn(calendar);
		assertEquals("the live day, and the logged-out marker in place of the bank clock", "1d vs 08 Sep - logged out",
			BankPriceMovementPanel.provenanceText(out, MovementWindow.D1, move, PRICES_AT, ViewOptions.DEFAULT));
		assertEquals("the pre-U arity is the guide-only footnote it always was",
			BankPriceMovementPanel.provenanceText(listedWith(summary()), MovementWindow.D1, move, PRICES_AT),
			BankPriceMovementPanel.provenanceText(live, MovementWindow.D1, move, PRICES_AT, LIVE_OFF));
	}

	@Test
	public void controlTextsAreThePureHelpers()
	{
		assertEquals("All items", BankPriceMovementPanel.bandLabel(0L, 0L));
		assertEquals("100k+", BankPriceMovementPanel.bandLabel(100_000L, 0L));
		assertEquals("up to 5m", BankPriceMovementPanel.bandLabel(0L, 5_000_000L));
		assertEquals("100k - 5m", BankPriceMovementPanel.bandLabel(100_000L, 5_000_000L));
		assertEquals("", BankPriceMovementPanel.bandSentence(0L, 0L));
		assertEquals("from 1m", BankPriceMovementPanel.bandSentence(1_000_000L, 0L));
		assertEquals("up to 5m", BankPriceMovementPanel.bandSentence(0L, 5_000_000L));
		assertEquals("from 100k to 5m", BankPriceMovementPanel.bandSentence(100_000L, 5_000_000L));
		assertEquals("Your band is 1m and up", BankPriceMovementPanel.bandDescription(1_000_000L, 0L));
		assertEquals("Your band is up to 5m", BankPriceMovementPanel.bandDescription(0L, 5_000_000L));
		assertEquals("Your band is from 100k to 5m", BankPriceMovementPanel.bandDescription(100_000L, 5_000_000L));
		assertEquals("Show 279 more", BankPriceMovementPanel.showMoreText(279));
		// Z3: the chip's BAND, not its place - the four bounds are the reader's now.
		assertTrue(BankPriceMovementPanel.presetLit(RowFilter.DEFAULT, 0L));
		assertFalse(BankPriceMovementPanel.presetLit(RowFilter.DEFAULT, 100_000L));
		assertTrue(BankPriceMovementPanel.presetLit(RowFilter.DEFAULT.withGpMin(1_000_000L), 1_000_000L));
		assertFalse("a custom band lights nothing", BankPriceMovementPanel.presetLit(RowFilter.DEFAULT.withGpMin(1_000_000L).withGpMax(5_000_000L), 1_000_000L));
	}

	/** M1/M6 step 4: the gp band is the rows' business; the card is the whole bank and a Min / Max change leaves it alone. */
	@Test
	public void aFilterChangeLeavesTheBankValueAlone() throws Exception
	{
		build();
		publish(rows(3), listedWith(summary()));
		onEdt(() -> assertTrue(panel.applyMin("1m")));
		verify(service).setFilter(RowFilter.DEFAULT.withGpMin(1_000_000L));
		assertEquals("1.23b", panel.totalLabel().getText());
		assertEquals("+1.0%", panel.pctLabel().getText());
		assertTrue(panel.heroShowing());

		// ...and the EMPTY card a tight band brings still carries the card with the same figures.
		publish(Collections.emptyList(), listedWith(summary()));
		assertEquals(BankPriceMovementPanel.CARD_EMPTY, panel.card());
		assertTrue(panel.heroShowing());
		assertEquals("1.23b", panel.totalLabel().getText());
		assertEquals("+1.0%", panel.pctLabel().getText());
	}

	/**
	 * O4 as addendum Q moved it (Q2): the GEAR menu - "Refresh prices now", the card's three check items, then
	 * the four view switches addendum AO left standing, in two separated groups - and the card's right-click
	 * menu is gone with it.
	 */
	@Test
	public void theGearMenuRefreshesAndTogglesTheThreeFigures() throws Exception
	{
		// AH: the hovers are ON here so that the assertion below - hiding the total takes its hover with it -
		// is about the FIGURE going away and not about the whole sidebar being silent.
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		final JPopupMenu menu = panel.heroMenu();
		assertNull("Q2: the card's right-click menu is REMOVED - the gear is the way in", panel.hero().getComponentPopupMenu());
		assertEquals(Arrays.asList(BankPriceMovementPanel.REFRESH_MENU_TEXT, BankPriceMovementPanel.SHOW_VALUE_TEXT,
			BankPriceMovementPanel.SHOW_GP_TEXT, BankPriceMovementPanel.SHOW_PCT_TEXT,
			BankPriceMovementPanel.LIVE_PRICES_TEXT, BankPriceMovementPanel.COUNT_CASH_TEXT,
			BankPriceMovementPanel.COUNT_UNTRADEABLES_TEXT, BankPriceMovementPanel.COUNT_INVENTORY_TEXT,
			// AH: the ninth and last ITEM, under the preset row. "Reset to default" is no longer among them -
			// AH2 made it a button in the bottom row beside OK, so it is a child of that row and not an entry.
			// It was the tenth until addendum AO deleted "Show stack value on rows" from the group above it.
			BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT), itemTexts(menu));
		// Z2: nine items - the eight switches and addendum AH's hover switch - with the preset row among them and,
		// under everything, addendum AB's OK row carrying AH2's "Reset to default" button at its left end.
		assertEquals("nine items, the preset row, the OK row and three separators", 14, menu.getComponentCount());
		assertTrue("the first separator is under Refresh", menu.getComponent(1) instanceof JSeparator);
		assertTrue("the second is between the card's three and the view's four", menu.getComponent(5) instanceof JSeparator);
		// T1: the price series leads the group - what a stack is worth is answered before whether it is counted.
		assertSame(panel.livePricesItem(), menu.getComponent(6));
		final List<JComponent> children = new ArrayList<>();
		descendants(panel.hero(), children);
		assertFalse(children.isEmpty());
		for (JComponent c : children)
		{
			assertNull(c.getClass().getSimpleName() + " has no popup of its own", c.getComponentPopupMenu());
		}
		for (JCheckBoxMenuItem item : new JCheckBoxMenuItem[]{panel.showValueItem(), panel.showGpItem(), panel.showPctItem()})
		{
			assertTrue(item.getText() + " is ticked while its figure is shown", item.isSelected());
			assertNotNull(item.getText() + " carries the config item's description", item.getToolTipText());
		}
		assertSame(panel.showValueItem(), item(menu, BankPriceMovementPanel.SHOW_VALUE_TEXT));
		assertEquals(BankPriceMovementPanel.SHOW_VALUE_TIP, panel.showValueItem().getToolTipText());
		assertEquals(BankPriceMovementPanel.SHOW_GP_TIP, panel.showGpItem().getToolTipText());
		assertEquals(BankPriceMovementPanel.SHOW_PCT_TIP, panel.showPctItem().getToolTipText());

		onEdt(() -> item(menu, BankPriceMovementPanel.REFRESH_MENU_TEXT).doClick(0));
		verify(service).refreshNow();

		final int rebuilds = panel.rebuilds();
		onEdt(() -> panel.showValueItem().doClick(0));
		assertEquals(HeroVisibility.of(false, true, true), panel.heroVisibility());
		assertFalse("the tick follows the click", panel.showValueItem().isSelected());
		assertFalse("the total left the card at once", panel.shows(panel.totalLabel()));
		assertNull("AF: the hover goes with the figure it explains", panel.hero().getToolTipText());
		assertEquals("the pref is written so the config panel follows", Arrays.asList(HeroVisibility.of(false, true, true)), prefs.heroSaves);
		assertTrue("the filter is not touched", prefs.saves.isEmpty());
		verify(service, never()).setFilter(any());
		assertEquals("the rows are not rebuilt for a card switch", rebuilds, panel.rebuilds());

		onEdt(() -> panel.showGpItem().doClick(0));
		assertEquals(HeroVisibility.of(false, false, true), panel.heroVisibility());
		assertTrue(panel.shows(panel.moveLine()));
		assertFalse(panel.shows(panel.deltaLabel()));
		assertTrue(panel.shows(panel.pctLabel()));

		onEdt(() -> panel.showPctItem().doClick(0));
		assertEquals(HeroVisibility.NONE, panel.heroVisibility());
		assertFalse("both off: the move line and the triangle go", panel.shows(panel.moveLine()));
		assertEquals(3, prefs.heroSaves.size());

		// The config's ConfigChanged comes back through applyHeroVisibility and writes nothing more.
		onEdt(() -> panel.applyHeroVisibility(HeroVisibility.NONE));
		assertEquals(3, prefs.heroSaves.size());

		onEdt(() -> panel.showValueItem().doClick(0));
		assertEquals(HeroVisibility.of(true, false, false), panel.heroVisibility());
		assertTrue(panel.shows(panel.totalLabel()));
		assertTrue(panel.showValueItem().isSelected());
		assertEquals(Arrays.asList(HeroVisibility.of(false, true, true), HeroVisibility.of(false, false, true), HeroVisibility.NONE,
			HeroVisibility.of(true, false, false)), prefs.heroSaves);
	}

	/** O2: applyHeroVisibility is the config's road - it repaints and ticks the menu, and saves nothing. */
	@Test
	public void applyHeroVisibilityRepaintsTheCardWithoutSaving() throws Exception
	{
		build();
		publish(rows(3), listedWith(summary()));
		onEdt(() -> panel.applyHeroVisibility(HeroVisibility.of(false, true, false)));
		onEdt(() ->
		{
			assertEquals(HeroVisibility.of(false, true, false), panel.heroVisibility());
			assertFalse(panel.shows(panel.totalLabel()));
			assertTrue(panel.shows(panel.deltaLabel()));
			assertFalse(panel.shows(panel.pctLabel()));
			assertFalse(panel.showValueItem().isSelected());
			assertTrue(panel.showGpItem().isSelected());
			assertFalse(panel.showPctItem().isSelected());
			assertTrue("no save loop", prefs.heroSaves.isEmpty());
			assertTrue(prefs.saves.isEmpty());
		});
		verify(service, never()).setFilter(any());

		onEdt(() -> panel.applyHeroVisibility(HeroVisibility.of(false, true, false)));
		assertTrue("the same value again is harmless", prefs.heroSaves.isEmpty());
		onEdt(() -> panel.applyHeroVisibility(null));
		assertEquals("null reads as every figure shown", HeroVisibility.ALL, panel.heroVisibility());
		assertTrue(panel.shows(panel.totalLabel()));
		assertTrue(panel.shows(panel.pctLabel()));
		assertTrue(prefs.heroSaves.isEmpty());

		// The switches survive a publish and a window change: they are the panel's, not the status's.
		publish(rows(3), listedWith(summary()));
		onEdt(() -> panel.applyHeroVisibility(HeroVisibility.NONE));
		publish(new ArrayList<>(rows(3)), listedWithProblem(COOLDOWN, MovementWindow.D1, THEN_DAY));
		onEdt(() -> panel.selectWindow(MovementWindow.D30));
		assertEquals(HeroVisibility.NONE, panel.heroVisibility());
		assertFalse(panel.shows(panel.totalLabel()));
		assertFalse(panel.shows(panel.moveLine()));
	}

	/** O4: setHeroVisibility applies at once AND writes the pref; a repeat, and the round trip, write nothing. */
	@Test
	public void setHeroVisibilityWritesThePrefAndAppliesAtOnce() throws Exception
	{
		build();
		onEdt(() -> panel.setHeroVisibility(HeroVisibility.NONE));
		assertEquals(HeroVisibility.NONE, panel.heroVisibility());
		assertEquals(Arrays.asList(HeroVisibility.NONE), prefs.heroSaves);
		onEdt(() -> panel.setHeroVisibility(HeroVisibility.NONE));
		assertEquals("already there: nothing written", Arrays.asList(HeroVisibility.NONE), prefs.heroSaves);
		onEdt(() -> panel.applyHeroVisibility(HeroVisibility.NONE));
		assertEquals(Arrays.asList(HeroVisibility.NONE), prefs.heroSaves);
		onEdt(() -> panel.setHeroVisibility(null));
		assertEquals("null reads as every figure shown, and that is a change", HeroVisibility.ALL, panel.heroVisibility());
		assertEquals(Arrays.asList(HeroVisibility.NONE, HeroVisibility.ALL), prefs.heroSaves);
		assertTrue(prefs.saves.isEmpty());
		// Before a bank is loaded the card is not in the header, but the switches are kept for when it is.
		assertFalse(panel.heroShowing());
		publish(rows(1), listedWith(summary()));
		assertTrue(panel.heroShowing());
		assertTrue(panel.shows(panel.totalLabel()));
	}

	// ---- addendum Q: the gear (Q1), its menu's view switches (Q2) and what they draw (Q4-Q6)

	/**
	 * Q1: the gear sits at the RIGHT end of the total's line, directly under the "Refresh" link, so the card
	 * gains no height for it; it says "Options"; it brightens under the mouse the way every other icon control
	 * on this panel does; and a right-click is not its gesture - the LEFT button opens the menu.
	 */
	@Test
	public void theGearSitsAtTheRightOfTheTotalLineAndSaysOptions() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			final BorderLayout line = (BorderLayout) panel.totalRow().getLayout();
			assertSame("the EAST end of the total's line", panel.gearLabel(), line.getLayoutComponent(BorderLayout.EAST));
			assertSame(panel.totalLabel(), line.getLayoutComponent(BorderLayout.WEST));
			assertTrue("on the card", panel.shows(panel.gearLabel()));
			assertEquals(BankPriceMovementPanel.OPTIONS_TIP, panel.gearLabel().getToolTipText());
			assertEquals("Options", BankPriceMovementPanel.OPTIONS_TIP);
			assertEquals("a 12 px glyph", Widgets.GEAR_SIZE, panel.gearLabel().getIcon().getIconWidth());
			assertTrue("it is the drawn gear at rest",
				sameIcon(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.LIGHT_GRAY_COLOR), panel.gearLabel().getIcon()));

			hover(panel.gearLabel(), true);
			assertTrue("and brightens under the mouse",
				sameIcon(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.BRAND_ORANGE), panel.gearLabel().getIcon()));
			hover(panel.gearLabel(), false);
			assertTrue(sameIcon(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.LIGHT_GRAY_COLOR), panel.gearLabel().getIcon()));

			// A press off the screen opens nothing rather than throwing: a popup can only be placed against a
			// component that is showing, which is the guard the order menu one row down carries too.
			press(panel.gearLabel());
			panel.openGearMenu();
			assertFalse("nothing was shown from an off-screen gear", panel.heroMenu().isVisible());
		});
		verify(service, never()).refreshNow();
	}

	/** Q3: the initial view switches come from the config, through the prefs seam; the menu ticks agree. */
	@Test
	public void theInitialViewOptionsComeFromThePrefs() throws Exception
	{
		prefs.storedOptions = new ViewOptions(false, true, true, false, false);
		build();
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			assertEquals(new ViewOptions(false, true, true, false, false), panel.options());
			assertFalse(panel.countCashItem().isSelected());
			assertTrue(panel.countUntradeablesItem().isSelected());
			// T1's switch reads through the seam as well; it stood beside the row switch addendum AO deleted.
			assertTrue(panel.livePricesItem().isSelected());
			assertFalse("Y1: the carried switch comes through the same seam", panel.countInventoryItem().isSelected());
			assertTrue("construction saves nothing", prefs.optionSaves.isEmpty());
		});

		// A fresh profile remembers nothing and gets the defaults: cash counted, live prices on, what the player
		// carries counted (Y1), untradeables and the data hovers off. A second panel needs a second service,
		// since build() pins that each one registers exactly one listener.
		service = mock(PriceService.class);
		prefs = new RecordingPrefs();
		build();
		onEdt(() ->
		{
			assertEquals(ViewOptions.DEFAULT, panel.options());
			assertTrue(panel.countCashItem().isSelected());
			assertTrue(panel.countInventoryItem().isSelected());
			assertTrue(panel.livePricesItem().isSelected());
			assertFalse(panel.countUntradeablesItem().isSelected());
		});
	}

	/**
	 * Q2: the gear menu's view items write the choice through {@code saveOptions} and apply it at once - the
	 * round trip the card's three make - and the tick follows the click. The config's own path back
	 * ({@link BankPriceMovementPanel#applyOptions}) writes nothing more.
	 *
	 * <p>Three of them are flipped here, which is what this test has always done; the third used to be the row
	 * switch addendum AO deleted and is addendum T's live switch now. The one it is does not matter - they all
	 * ride the same {@code setOptions} road - only that a flip of one carries every other switch through
	 * untouched.
	 */
	@Test
	public void theViewSwitchesWriteThePrefAndApplyAtOnce() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		for (JCheckBoxMenuItem item : new JCheckBoxMenuItem[]{panel.countCashItem(), panel.countUntradeablesItem(),
			panel.livePricesItem()})
		{
			assertNotNull(item.getText() + " carries the config item's description", item.getToolTipText());
		}
		assertEquals(BankPriceMovementPanel.COUNT_CASH_TIP, panel.countCashItem().getToolTipText());
		assertEquals(BankPriceMovementPanel.COUNT_UNTRADEABLES_TIP, panel.countUntradeablesItem().getToolTipText());
		assertEquals(BankPriceMovementPanel.LIVE_PRICES_TIP, panel.livePricesItem().getToolTipText());
		assertTrue("cash is counted by default", panel.countCashItem().isSelected());

		// Every expected value below is wrapped in hovers(...): the three items' own tooltips are read above,
		// and since AH3 there are none to read unless the hover switch is on, so this panel was built with it
		// on. It is the fifth field of ViewOptions and no business of these three switches - which is the
		// point of carrying it through unchanged rather than letting a flip drop it.
		onEdt(() -> panel.countUntradeablesItem().doClick(0));
		assertEquals(hovers(ViewOptions.DEFAULT.withCountUntradeables(true)), panel.options());
		assertTrue("the tick follows the click", panel.countUntradeablesItem().isSelected());
		assertEquals(Arrays.asList(hovers(ViewOptions.DEFAULT.withCountUntradeables(true))), prefs.optionSaves);

		onEdt(() -> panel.countCashItem().doClick(0));
		assertEquals(hovers(new ViewOptions(false, true, true, true, false)), panel.options());
		onEdt(() -> panel.livePricesItem().doClick(0));
		assertEquals(hovers(new ViewOptions(false, true, false, true, false)), panel.options());
		assertEquals(3, prefs.optionSaves.size());
		assertEquals(hovers(new ViewOptions(false, true, false, true, false)), prefs.optionSaves.get(2));

		// The config's ConfigChanged comes back through applyOptions and writes nothing more.
		onEdt(() -> panel.applyOptions(hovers(new ViewOptions(false, true, false, true, false))));
		assertEquals(3, prefs.optionSaves.size());
		assertTrue("the hero switches and the filter are untouched", prefs.heroSaves.isEmpty());
		assertTrue(prefs.saves.isEmpty());
		verify(service, never()).setFilter(any());

		onEdt(() -> panel.countCashItem().doClick(0));
		assertEquals(hovers(new ViewOptions(true, true, false, true, false)), panel.options());
		assertTrue(panel.countCashItem().isSelected());
	}

	/**
	 * Y4: the gear menu in plain words - the nine entries addendum AO leaves, in the order they are drawn,
	 * spelled out.
	 *
	 * <p>The literals are the point. Every other assertion in this file reads these labels through the panel's
	 * constants, so a rename sails through all of them; the user asked for a pass over the WORDS ("Perhaps we
	 * should come up with more layman names for the other selections") and this is where the words are pinned.
	 * The old ones are checked to be gone rather than merely unused, because a stale label left on the settings
	 * page beside a renamed menu entry is the one failure a constant cannot catch.
	 */
	@Test
	public void theGearMenuReadsInThePlainWordsOfY4() throws Exception
	{
		build();
		final List<String> texts = itemTexts(panel.heroMenu());
		assertEquals(Arrays.asList(
			"Refresh prices now",
			"Show bank value",
			"Show change in gp",
			"Show change in %",
			"Use live prices",
			"Include coins and platinum tokens",
			"Include untradeable items",
			"Include inventory and worn gear",
			// AH: the ninth and last entry, under the preset row and its rule - the only switch carrying a box
			// it draws itself. "Reset to default" was an entry until AH2 moved it into the bottom row beside
			// OK, so it is pinned by the bottom-row test instead.
			"Show hover text"), texts);
		for (String gone : new String[]{"Show bank move (gp)", "Show bank move (%)", "Live prices",
			"Count coins and platinum", "Count untradeable items", "Show holding value on rows"})
		{
			assertFalse("the pre-Y label is gone: " + gone, texts.contains(gone));
		}
		// AO1: and the row switch's own Y4 name is gone the same way, asserted rather than merely unused -
		// this is the file that pins the WORDS, so a deleted item left drawn on the settings page would show
		// up here or nowhere.
		assertFalse("AO1: the row switch is deleted, label and all", texts.contains("Show stack value on rows"));
		// The two switch groups are unmoved: the card's three say what is DRAWN, the four under them what the
		// figures MEAN, addendum Z's bands come after both, addendum AH's hover switch after those and
		// addendum AB's OK row - now carrying "Reset to default" as well (AH2) - after everything.
		assertFalse("AH2: the way back to the default bands is a button in that row, not an entry",
			texts.contains(BankPriceMovementPanel.RESET_PRESETS_TEXT));
		assertEquals("nine items, the preset row, the OK row and three separators", 14,
			panel.heroMenu().getComponentCount());
	}

	/**
	 * Y1: the new switch is a check item of the second group, directly after "Include untradeable items" - it is a
	 * third thing INCLUDED, and since addendum AO deleted the row switch that used to sit under it, the last of
	 * the group. It carries its config item's own description, is ticked out of the box (default ON), writes
	 * through {@code saveOptions} the way the other three do, and rebuilds no rows: which stacks exist and what
	 * each one's quantity is are the service's answer to the same switch, and arrive as an ordinary publish.
	 */
	@Test
	public void theCarriedSwitchSitsWithTheIncludesAndWritesThePref() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		final JPopupMenu menu = panel.heroMenu();
		assertSame(panel.countInventoryItem(), item(menu, BankPriceMovementPanel.COUNT_INVENTORY_TEXT));
		assertSame("directly after the untradeables", panel.countInventoryItem(), menu.getComponent(9));
		assertTrue("AO1: and last of its group now, with the separator under it",
			menu.getComponent(10) instanceof JSeparator);
		assertEquals("the name addendum Y asks for, pinned", "Include inventory and worn gear",
			BankPriceMovementPanel.COUNT_INVENTORY_TEXT);
		assertEquals("the description addendum Y asks for, pinned",
			"Items in your inventory and worn gear count in the bank value and are listed with the bank's stacks."
				+ " They are read when you open the bank or press Refresh.",
			BankPriceMovementPanel.COUNT_INVENTORY_TIP);
		assertEquals(BankPriceMovementPanel.COUNT_INVENTORY_TIP, panel.countInventoryItem().getToolTipText());
		assertTrue("on by default (Y1)", panel.countInventoryItem().isSelected());
		assertTrue(panel.options().countInventory());
		assertEquals("construction saves nothing", Collections.emptyList(), prefs.optionSaves);

		final int rebuilds = panel.rebuilds();
		onEdt(() -> panel.countInventoryItem().doClick(0));
		// hovers(...) because this panel was built with AH's switch on - the item's own tooltip is read above,
		// and AH3 leaves none to read otherwise. It is the fifth field and rides through every flip unchanged.
		assertEquals(hovers(ViewOptions.DEFAULT.withCountInventory(false)), panel.options());
		assertFalse("the tick follows the click", panel.countInventoryItem().isSelected());
		assertEquals("the pref is written so the config panel follows",
			Arrays.asList(hovers(ViewOptions.DEFAULT.withCountInventory(false))), prefs.optionSaves);
		assertEquals("the rows wait for the service's own recompute", rebuilds, panel.rebuilds());
		assertTrue("the filter and the hero switches are untouched", prefs.saves.isEmpty());
		assertTrue(prefs.heroSaves.isEmpty());
		verify(service, never()).setFilter(any());
		// Y1: the key of state.options named "inventory" - the fourth since addendum AO took "holding" out from
		// between the untradeables and the live switch - and since AH the fifth and last is "hover", which is
		// TRUE here because this panel was built with it on.
		assertTrue(panel.describe(), panel.describe().contains(
			",\"options\":{\"cash\":true,\"untradeables\":false,\"live\":true,\"inventory\":false,\"hover\":true}"));

		// The settings page's own change comes back through applyOptions: it ticks and writes nothing more.
		onEdt(() -> panel.applyOptions(hovers(ViewOptions.DEFAULT)));
		assertTrue("the tick follows the config", panel.countInventoryItem().isSelected());
		assertEquals(1, prefs.optionSaves.size());
		assertEquals(rebuilds, panel.rebuilds());
		assertTrue(panel.describe(), panel.describe().contains(
			",\"options\":{\"cash\":true,\"untradeables\":false,\"live\":true,\"inventory\":true,\"hover\":true}"));
	}

	/**
	 * Q6 as addendum AO leaves it: {@code applyOptions} is the config's road - it repaints and ticks the menu
	 * and saves nothing - and it rebuilds the ROWS for NONE of the switches at all.
	 *
	 * <p><b>The rule this test pins has been inverted, and that is the point of it.</b> Q6 gave the road one
	 * switch that threw the row panels away and built them again, {@code holdingOnRows}, because that one
	 * decided what a row PRINTED. The Q4 row format made the face print both readings - the stack on line 2,
	 * one item on line 3 - which left the switch reaching nothing drawn, and addendum AO line AO1 deleted it on
	 * the user's word ("if it doesnt do anything anymore then remove it"). So every switch that remains is
	 * about what the figures MEAN, which is the service's answer and arrives as an ordinary publish, and a
	 * rebuild here would now be a rebuild for nothing.
	 *
	 * <p>The rebuild counter is still what is read, and it must stay flat through every one of them - including
	 * addendum AH's hover switch, which is the one switch that does reach the open page and reaches it through
	 * {@code applyHoverSwitch} rather than by building the rows again.
	 */
	@Test
	public void applyOptionsRepaintsWithoutSavingAndRebuildsTheRowsForNoSwitchAtAll() throws Exception
	{
		build();
		publish(rows(3), listedWith(summary()));
		final int built = panel.rebuilds();
		onEdt(() ->
		{
			// The third row of the fixture is a stack of three at 3,000 gp each, and the Q4 face prints the
			// STACK on line 2 - the reading the deleted switch used to have to be ON for.
			assertEquals("the face is the stack: 3 x 3,000", "9,000", panel.rowPanels().get(2).priceText());
			assertEquals("...and the stack's move: 3 x +100", "+300", panel.rowPanels().get(2).gpText());

			panel.applyOptions(ViewOptions.DEFAULT.withCountCash(false));
			assertEquals(built, panel.rebuilds());
			panel.applyOptions(new ViewOptions(false, true, true, true, false));
			assertEquals("a switch the rows do not read leaves them alone", built, panel.rebuilds());
			assertFalse(panel.countCashItem().isSelected());
			assertTrue(panel.countUntradeablesItem().isSelected());

			panel.applyOptions(new ViewOptions(false, true, false, false, false));
			assertEquals("AO1: and so does every other one, now that the row switch is gone", built,
				panel.rebuilds());
			assertEquals("the face is unmoved", "9,000", panel.rowPanels().get(2).priceText());
			assertEquals("+300", panel.rowPanels().get(2).gpText());
			assertFalse(panel.livePricesItem().isSelected());
			assertFalse(panel.countInventoryItem().isSelected());

			// AH3's switch is the one that does reach the open page, and it reaches it without a rebuild.
			panel.applyOptions(new ViewOptions(false, true, false, false, true));
			assertEquals("the hover switch repaints the hovers, not the rows", built, panel.rebuilds());

			panel.applyOptions(new ViewOptions(false, true, false, false, true));
			assertEquals("the same value again does nothing at all", built, panel.rebuilds());
			panel.applyOptions(null);
			assertEquals("null reads as the defaults", ViewOptions.DEFAULT, panel.options());
			assertEquals("9,000", panel.rowPanels().get(2).priceText());
			assertEquals(built, panel.rebuilds());
			assertTrue(prefs.optionSaves.isEmpty());
		});

		// And the switches survive a publish: they are the panel's, not the status's.
		onEdt(() -> panel.applyOptions(ViewOptions.DEFAULT.withCountUntradeables(true)));
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			assertEquals(ViewOptions.DEFAULT.withCountUntradeables(true), panel.options());
			assertEquals("9,000", panel.rowPanels().get(2).priceText());
		});
	}

	/**
	 * R4, through the panel rather than through {@link MovementRowPanel} alone: a stack the service valued at its
	 * tradeable parts is built into the list as an ordinary PRICED row - its own price, its own gp figure, its
	 * own percentage, no "alch" tag - and the one thing that says where that price came from is the extra
	 * tooltip line naming the parts.
	 */
	@Test
	public void aPartsRowIsListedAsAnOrdinaryPricedRowWithThePartsOnItsTooltip() throws Exception
	{
		build();
		final List<MovementRow> listed = new ArrayList<>(rows(2));
		listed.add(LookRenderer.crystalBody());
		publish(listed, listedWith(summary()));
		onEdt(() ->
		{
			final MovementRowPanel row = panel.rowPanels().get(2);
			assertEquals("Crystal body", row.row().name());
			assertEquals(MovementRow.PriceSource.PARTS, row.row().source());
			assertEquals("16.6m", row.priceText());
			assertEquals("the move figures, not a tag", "-1.60m", row.gpText());
			assertEquals("-8.7%", row.changeText());
			assertNotEquals(MovementRowPanel.ALCH_TAG, row.changeText());
			assertTrue(row.tooltipHtml(), row.tooltipHtml()
				.contains("<br>Untradeable - valued as its parts: 3 x Crystal armour seed<br>"));
		});
	}

	/** The Refresh link asks the service; it goes orange under the mouse. */
	@Test
	public void refreshLinkAsksTheService() throws Exception
	{
		buildWithHovers();
		publish(rows(1), listed(1, 1));
		onEdt(() ->
		{
			assertEquals(BankPriceMovementPanel.REFRESH_TEXT, panel.refreshLabel().getText());
			assertEquals(BankPriceMovementPanel.REFRESH_TIP, panel.refreshLabel().getToolTipText());
			assertEquals(11f, panel.refreshLabel().getFont().getSize2D(), 0f);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.refreshLabel().getForeground());
			hover(panel.refreshLabel(), true);
			assertEquals(ColorScheme.BRAND_ORANGE, panel.refreshLabel().getForeground());
			hover(panel.refreshLabel(), false);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.refreshLabel().getForeground());
			assertTrue("the link lives in the caption row", SwingUtilities.isDescendingFrom(panel.refreshLabel(), panel.captionRow()));
			press(panel.refreshLabel());
		});
		verify(service, times(1)).refreshNow();
	}

	/**
	 * N 3.1 / O4: a right-click anywhere on the hero card is the card's menu, and the card keeps its window
	 * chips and its "Refresh" link INSIDE it - so the right button must press nothing under it, or one gesture
	 * would get two answers (the menu AND a new window). Only the left button presses ({@link Widgets#isPress}).
	 */
	@Test
	public void aRightButtonPressActsOnNothing() throws Exception
	{
		build();
		publish(rows(1), listed(1, 1));
		onEdt(() ->
		{
			rightPress(panel.windowChip(MovementWindow.D7));
			rightPress(panel.gearLabel());
			rightPress(panel.refreshLabel());
			rightPress(panel.bandTarget());
			rightPress(panel.clearBoundsLabel());
			assertEquals("the window did not move", RowFilter.DEFAULT, panel.filter());
			assertFalse("the fold did not open", panel.foldOpen());
		});
		assertTrue(prefs.saves.isEmpty());
		verify(service, never()).refreshNow();
		verify(service, never()).setFilter(any());
	}

	// ---- the window chips

	/** K4 / N 4.4: five chips in the GE site's order, exactly one lit - bold orange text over an orange rule. */
	@Test
	public void thereAreFiveWindowChipsAndOnlyOneIsEverLit() throws Exception
	{
		build();
		onEdt(() ->
		{
			assertEquals("K4 gives the sidebar five spans", 5, MovementWindow.values().length);
			final List<String> labels = new ArrayList<>();
			for (MovementWindow w : MovementWindow.values())
			{
				final JLabel chip = panel.windowChip(w);
				assertNotNull("every window has a chip: " + w, chip);
				assertEquals(w.label(), chip.getText());
				assertEquals(SwingConstants.CENTER, chip.getHorizontalAlignment());
				labels.add(chip.getText());
			}
			assertEquals(Arrays.asList("1d", "7d", "30d", "90d", "180d"), labels);
			assertEquals(Arrays.asList("1d", "7d", "30d", "90d", "180d"), texts(panel.chipRow()));

			for (MovementWindow chosen : MovementWindow.values())
			{
				panel.selectWindow(chosen);
				for (MovementWindow w : MovementWindow.values())
				{
					assertEquals(w + " lit while " + chosen + " is chosen", w == chosen, Widgets.isLit(panel.windowChip(w)));
				}
				final JLabel lit = panel.windowChip(chosen);
				assertEquals(ColorScheme.BRAND_ORANGE, lit.getForeground());
				assertTrue(lit.getFont().isBold());
				assertTrue(lit.getBorder() instanceof MatteBorder);
				assertEquals(ColorScheme.BRAND_ORANGE, ((MatteBorder) lit.getBorder()).getMatteColor());
			}
		});
	}

	/** N 3.2: a press on an unlit chip selects it; on the lit one it is a no-op. */
	@Test
	public void clickingAnUnlitChipSelectsItAndTheLitOneIsANoOp() throws Exception
	{
		build();
		onEdt(() -> press(panel.windowChip(MovementWindow.D7)));
		assertEquals(RowFilter.DEFAULT.withWindow(MovementWindow.D7), panel.filter());
		assertEquals(1, prefs.saves.size());
		verify(service).setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D7));

		onEdt(() -> press(panel.windowChip(MovementWindow.D7)));
		assertEquals("the lit chip swallows the press", 1, prefs.saves.size());
		verify(service, times(1)).setFilter(any());

		onEdt(() -> press(panel.windowChip(MovementWindow.D1)));
		assertEquals(RowFilter.DEFAULT, panel.filter());
		assertEquals(2, prefs.saves.size());
	}

	/** N 4.4: hover on an UNLIT chip only - orange text; the lit chip does not react, and both are one height. */
	@Test
	public void chipHoverPaintsAnUnlitChipOrangeAndLeavesTheLitOneAlone() throws Exception
	{
		build();
		onEdt(() ->
		{
			final JLabel unlit = panel.windowChip(MovementWindow.D7);
			final JLabel lit = panel.windowChip(MovementWindow.D1);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, unlit.getForeground());
			hover(unlit, true);
			assertEquals(ColorScheme.BRAND_ORANGE, unlit.getForeground());
			hover(unlit, false);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, unlit.getForeground());
			assertFalse(unlit.getFont().isBold());
			hover(lit, true);
			assertEquals("the lit chip is already the answer", ColorScheme.BRAND_ORANGE, lit.getForeground());
			hover(lit, false);
			assertEquals(ColorScheme.BRAND_ORANGE, lit.getForeground());
			assertTrue(lit.getFont().isBold());
			assertEquals("a lit and an unlit chip are the same height (N 4.4)", unlit.getPreferredSize().height, lit.getPreferredSize().height);
		});
	}

	/** N 3.2: "Guide-price change over the last 30d", plus " - baseline 10 Aug" when the window has one. */
	@Test
	public void chipTooltipsNameTheBaselineDay() throws Exception
	{
		buildWithHovers();
		assertEquals("Guide-price change over the last 1d", panel.windowChip(MovementWindow.D1).getToolTipText());
		publish(rows(3), listedWith(summary()));
		assertEquals("Guide-price change over the last 1d - baseline 07 Sep", panel.windowChip(MovementWindow.D1).getToolTipText());
		assertEquals("Guide-price change over the last 30d - baseline 09 Aug", panel.windowChip(MovementWindow.D30).getToolTipText());
		assertEquals("Guide-price change over the last 180d", panel.windowChip(MovementWindow.D180).getToolTipText());
	}

	// ---- addendum AM: which end of the control row each control stands at

	/**
	 * AM: the two controls have swapped ends - the price band WEST, the sort column EAST.
	 *
	 * <p>The user, looking at the live sidebar: "please swap the location of these menu buttons, i would like
	 * the sort filter on the right and the 1,000+ on the left." They had stood the other way round since
	 * addendum N, and nothing else about either moved: each keeps its own text, its own arrow and its own
	 * fitting rule, and the row still holds exactly the two of them.
	 *
	 * <p><b>Pinned by SLOT and not by index.</b> {@code getComponents()} would answer the order the two were
	 * added in, which is not what a reader sees - a {@code BorderLayout} draws by CONSTRAINT, so a later tidy-up
	 * that reordered the two {@code add} calls without touching their constraints would change the answer to
	 * this test while changing nothing on the sidebar, and a swap back to addendum N's arrangement done by
	 * re-labelling the constraints would pass one written by index. The constraint is the rule; ask for the
	 * constraint.
	 */
	@Test
	public void theControlRowStandsTheBandWestAndTheSortEast() throws Exception
	{
		build();
		onEdt(() ->
		{
			final BorderLayout layout = (BorderLayout) panel.controlRow().getLayout();
			assertSame("AM: the price band is the LEFT-hand control", panel.bandTarget(),
				layout.getLayoutComponent(BorderLayout.WEST));
			assertSame("AM: and the sort column the RIGHT-hand one", panel.sortButton(),
				layout.getLayoutComponent(BorderLayout.EAST));
			assertNull("nothing stands between them", layout.getLayoutComponent(BorderLayout.CENTER));
			assertEquals("...and the row holds those two and nothing else", 2,
				panel.controlRow().getComponentCount());
		});
	}

	// ---- the control row: sort

	/**
	 * W2 / W3: the button prints the lit COLUMN and wears the direction as its one glyph, and the menu carries the
	 * four columns in {@link SortMode}'s order with that same arrow on the lit one alone.
	 */
	@Test
	public void sortButtonNamesTheLitColumnAndTheMenuCarriesTheFourWithItsArrow() throws Exception
	{
		build();
		onEdt(() ->
		{
			assertEquals(SwingConstants.LEFT, panel.sortButton().getHorizontalTextPosition());
			assertTrue(panel.sortButton().getFont().isBold());
			assertEquals(12f, panel.sortButton().getFont().getSize2D(), 0f);
			assertEquals(ColorScheme.TEXT_COLOR, panel.sortButton().getForeground());
			hover(panel.sortButton(), true);
			assertEquals(ColorScheme.BRAND_ORANGE, panel.sortButton().getForeground());
			hover(panel.sortButton(), false);
			assertEquals(ColorScheme.TEXT_COLOR, panel.sortButton().getForeground());

			for (SortMode column : SortMode.values())
			{
				for (boolean descending : new boolean[]{true, false})
				{
					panel.setSort(column, descending);
					assertEquals("the button names the column in force", column.label(), panel.sortButton().getText());
					assertNotNull("the button always wears an arrow", panel.sortButton().getIcon());
					assertTrue(column + " " + descending + ": down for biggest first, up for smallest",
						sameIcon(Widgets.triangle(descending), panel.sortButton().getIcon()));

					final JPopupMenu menu = panel.buildSortMenu();
					final List<JMenuItem> items = items(menu);
					assertEquals("four columns", 4, items.size());
					assertEquals("four entries and no separators", 4, menu.getComponentCount());
					for (int i = 0; i < items.size(); i++)
					{
						final SortMode entry = SortMode.values()[i];
						final JMenuItem item = items.get(i);
						assertEquals("the label alone - no HTML, no grey qualifier (W3)", entry.label(), item.getText());
						assertEquals(12f, item.getFont().getSize2D(), 0f);
						final boolean on = entry == column;
						assertEquals(entry + " lit while " + column, on ? ColorScheme.BRAND_ORANGE : ColorScheme.TEXT_COLOR,
							item.getForeground());
						if (on)
						{
							assertTrue(entry + " carries the button's own arrow",
								sameIcon(Widgets.triangle(descending), item.getIcon()));
						}
						else
						{
							assertNull(entry + " is not lit, so it carries no arrow", item.getIcon());
						}
					}
				}
			}
		});
	}

	/**
	 * X1: every entry of the sort menu says what PICKING it will do - the lit column's reads "Toggle
	 * &lt;label&gt;" and the other three "Sort by &lt;label&gt;", the label verbatim. The entry texts, the
	 * arrow and the orange are untouched; the menu is rebuilt on each open, so the sentences follow the lit
	 * column.
	 */
	@Test
	public void theSortMenuEntriesSayToggleTheLitColumnAndSortByTheOthers() throws Exception
	{
		buildWithHovers();
		onEdt(() ->
		{
			for (SortMode column : SortMode.values())
			{
				for (boolean descending : new boolean[]{true, false})
				{
					panel.setSort(column, descending);
					final List<JMenuItem> entries = items(panel.buildSortMenu());
					assertEquals(4, entries.size());
					for (JMenuItem entry : entries)
					{
						final boolean lit = entry.getText().equals(column.label());
						assertEquals(column + " lit, hovering " + entry.getText(),
							(lit ? "Toggle " : "Sort by ") + entry.getText(), entry.getToolTipText());
					}
				}
			}

			// The sentences in full, so the exact words are pinned and not only their shape.
			panel.setSort(SortMode.STACK_VALUE, true);
			assertEquals("Toggle Stack price", item(panel.buildSortMenu(), "Stack price").getToolTipText());
			assertEquals("Sort by Percent change", item(panel.buildSortMenu(), "Percent change").getToolTipText());
			assertEquals("Sort by gp change", item(panel.buildSortMenu(), "gp change").getToolTipText());
			assertEquals("Sort by Item price", item(panel.buildSortMenu(), "Item price").getToolTipText());

			// ...and they follow the lit column, which is what the rebuild on each open is for.
			panel.setSort(SortMode.PERCENT_MOVE, true);
			assertEquals("Toggle Percent change", item(panel.buildSortMenu(), "Percent change").getToolTipText());
			assertEquals("Sort by Stack price", item(panel.buildSortMenu(), "Stack price").getToolTipText());

			// The names themselves did not change with the hover (X1).
			assertEquals(Arrays.asList("Percent change", "gp change", "Item price", "Stack price"),
				itemTexts(panel.buildSortMenu()));
		});
	}

	/**
	 * X2: the sort button's hover is the one phrase "Change sorting" - under every column, both directions and
	 * either reading of a view switch, and from the first paint on. The column and the direction are on the
	 * button's face (W2) and what a press does is on the entry (X1), so the hover only names the control.
	 *
	 * <p>The switch looped here was {@code holdingOnRows} until addendum AO deleted it; it is addendum T's live
	 * switch now, which is the one that remains and that changes what the columns are computed from.
	 */
	@Test
	public void theSortButtonsHoverIsOnePhraseUnderEveryColumnAndSwitch() throws Exception
	{
		assertEquals("Change sorting", BankPriceMovementPanel.SORT_BUTTON_TIP);
		buildWithHovers();
		onEdt(() ->
		{
			assertEquals("set before the panel is ever shown", "Change sorting",
				panel.sortButton().getToolTipText());
			for (boolean live : new boolean[]{false, true})
			{
				panel.applyOptions(hovers(ViewOptions.DEFAULT.withLivePrices(live)));
				for (SortMode column : SortMode.values())
				{
					for (boolean descending : new boolean[]{true, false})
					{
						panel.setSort(column, descending);
						assertEquals(column + " " + descending + " live=" + live, "Change sorting",
							panel.sortButton().getToolTipText());
					}
				}
			}
		});
	}

	/**
	 * W3: an entry is a PRESS - the lit column picked again flips the direction, another column lights biggest
	 * first whatever the one before it was doing - through the same save-and-tell path as every other click.
	 */
	@Test
	public void pickingAColumnFlipsItWhenItIsLitAndStartsBiggestFirstWhenItIsNot() throws Exception
	{
		build();
		assertEquals(RowFilter.DEFAULT, panel.filter());

		// The lit column again: the direction turns over and nothing else moves.
		onEdt(() -> item(panel.buildSortMenu(), "Percent change").doClick(0));
		assertEquals(RowFilter.DEFAULT.withDescending(false), panel.filter());
		onEdt(() -> item(panel.buildSortMenu(), "Percent change").doClick(0));
		assertEquals(RowFilter.DEFAULT, panel.filter());

		// Another column, chosen while the list runs the other way: it starts biggest first.
		onEdt(() -> item(panel.buildSortMenu(), "Percent change").doClick(0));
		assertFalse(panel.filter().descending());
		onEdt(() -> item(panel.buildSortMenu(), "Stack price").doClick(0));
		assertEquals("a new column starts biggest first",
			RowFilter.DEFAULT.withSort(SortMode.STACK_VALUE).withDescending(true), panel.filter());
		onEdt(() -> item(panel.buildSortMenu(), "Stack price").doClick(0));
		assertEquals(RowFilter.DEFAULT.withSort(SortMode.STACK_VALUE).withDescending(false), panel.filter());
		onEdt(() -> item(panel.buildSortMenu(), "gp change").doClick(0));
		assertEquals(RowFilter.DEFAULT.withSort(SortMode.GP_MOVE).withDescending(true), panel.filter());
		onEdt(() -> item(panel.buildSortMenu(), "Item price").doClick(0));
		assertEquals(RowFilter.DEFAULT.withSort(SortMode.UNIT_PRICE).withDescending(true), panel.filter());

		assertEquals(7, prefs.saves.size());
		final InOrder order = inOrder(service);
		order.verify(service).setFilter(RowFilter.DEFAULT.withDescending(false));
		order.verify(service).setFilter(RowFilter.DEFAULT);
		order.verify(service).setFilter(RowFilter.DEFAULT.withDescending(false));
		order.verify(service).setFilter(RowFilter.DEFAULT.withSort(SortMode.STACK_VALUE).withDescending(true));
		order.verify(service).setFilter(RowFilter.DEFAULT.withSort(SortMode.STACK_VALUE).withDescending(false));
		order.verify(service).setFilter(RowFilter.DEFAULT.withSort(SortMode.GP_MOVE).withDescending(true));
		order.verify(service).setFilter(RowFilter.DEFAULT.withSort(SortMode.UNIT_PRICE).withDescending(true));

		// The pair setter is the config's road, not a menu entry's: it states both and flips nothing.
		onEdt(() -> panel.setSort(SortMode.UNIT_PRICE, true));
		assertEquals("the same pair again is not saved again", 7, prefs.saves.size());
		onEdt(() -> panel.setSort(null, true));
		assertEquals("a null column reads as the percent move",
			RowFilter.DEFAULT.withSort(SortMode.PERCENT_MOVE).withDescending(true), panel.filter());
		assertEquals(8, prefs.saves.size());
	}

	/** W4: the bridge's sort= presses a column exactly as the menu does, and dir= is the direction alone. */
	@Test
	public void clickSortPressesAColumnAndSetDescendingSetsTheDirection() throws Exception
	{
		build();
		onEdt(() -> panel.clickSort(SortMode.GP_MOVE));
		assertEquals(RowFilter.DEFAULT.withSort(SortMode.GP_MOVE), panel.filter());
		assertTrue("a new column starts biggest first", panel.filter().descending());
		assertEquals("gp change", panel.sortButton().getText());

		onEdt(() -> panel.clickSort(SortMode.GP_MOVE));
		assertFalse("the lit column flips", panel.filter().descending());
		assertEquals("gp change", panel.sortButton().getText());
		assertTrue("and the arrow turns over with it", sameIcon(Widgets.triangle(false), panel.sortButton().getIcon()));

		onEdt(() -> panel.clickSort(SortMode.UNIT_PRICE));
		assertTrue("another column starts biggest first, whatever this one was doing", panel.filter().descending());
		assertEquals(SortMode.UNIT_PRICE, panel.filter().sort());
		assertEquals("Item price", panel.sortButton().getText());
		assertTrue(sameIcon(Widgets.triangle(true), panel.sortButton().getIcon()));

		onEdt(() -> panel.setDescending(false));
		assertEquals("Item price", panel.sortButton().getText());
		assertTrue(sameIcon(Widgets.triangle(false), panel.sortButton().getIcon()));
		onEdt(() -> panel.setDescending(false));
		assertEquals("an unchanged filter is not saved again", 4, prefs.saves.size());
		onEdt(() -> panel.clickSort(null));
		onEdt(() -> panel.selectWindow(null));
		assertEquals("nulls are ignored", 4, prefs.saves.size());
	}

	// ---- the control row: the band button

	/** N 4.4 control 3: the band button states its band, orange under the mouse, and opens the fold. */
	@Test
	public void bandButtonStatesItsBand() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listed(529, 3));
		onEdt(() ->
		{
			assertEquals("All items", panel.bandTarget().getText());
			assertEquals(ColorScheme.TEXT_COLOR, panel.bandTarget().getForeground());
			assertTrue(sameIcon(Widgets.triangle(true), panel.bandTarget().getIcon()));
			assertEquals(SwingConstants.LEFT, panel.bandTarget().getHorizontalTextPosition());
			assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText()
				.startsWith(BankPriceMovementPanel.BAND_TIP));
			assertEquals(12f, panel.bandTarget().getFont().getSize2D(), 0f);
			hover(panel.bandTarget(), true);
			assertEquals(ColorScheme.BRAND_ORANGE, panel.bandTarget().getForeground());
			hover(panel.bandTarget(), false);
			assertEquals(ColorScheme.TEXT_COLOR, panel.bandTarget().getForeground());
		});
		onEdt(() -> panel.applyMin("100k"));
		assertEquals("100k+", panel.bandTarget().getText());
		assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText()
			.startsWith("Showing items from 100k -"));
		onEdt(() -> panel.applyMax("5m"));
		assertEquals("100k - 5m", panel.bandTarget().getText());
		assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText()
			.startsWith("Showing items from 100k to 5m -"));
		onEdt(() -> panel.applyMin(""));
		assertEquals("up to 5m", panel.bandTarget().getText());
		assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText()
			.startsWith("Showing items up to 5m -"));
		assertEquals(ColorScheme.TEXT_COLOR, panel.bandTarget().getForeground());

		publish(Collections.emptyList(), listed(529, 0));
		assertEquals(BankPriceMovementPanel.CARD_EMPTY, panel.card());
		assertEquals("the band that emptied the list is still stated", "up to 5m", panel.bandTarget().getText());

		onEdt(() -> panel.applyBand(0L, 0L));
		assertEquals("All items", panel.bandTarget().getText());
		assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText()
			.startsWith(BankPriceMovementPanel.BAND_TIP));
	}

	// ---- the price fold

	/** N 3.5: added on a tap, removed on the second; closing never clears the band. */
	@Test
	public void theFoldIsAddedOnATapAndRemovedOnTheSecondAndTheBandSurvives() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() ->
		{
			assertFalse(panel.foldOpen());
			assertEquals(2, panel.header().getComponentCount());
			press(panel.bandTarget());
			assertTrue(panel.foldOpen());
			assertSame(panel.fold(), panel.header().getComponent(2));
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, panel.fold().getBackground());
			assertTrue("the fields live in the fold", SwingUtilities.isDescendingFrom(panel.minField(), panel.fold()));
			assertTrue(SwingUtilities.isDescendingFrom(panel.maxField(), panel.fold()));
			assertTrue(SwingUtilities.isDescendingFrom(panel.clearBoundsLabel(), panel.fold()));

			final JTextField tf = panel.minField().getTextField();
			tf.setText("1m");
			tf.postActionEvent();
			assertEquals(1_000_000L, panel.filter().gpMin());

			press(panel.bandTarget());
			assertFalse(panel.foldOpen());
			assertEquals("the fold is removed, not hidden", 2, panel.header().getComponentCount());
			assertEquals("the band survives the close", 1_000_000L, panel.filter().gpMin());
			assertEquals("1m+", panel.bandTarget().getText());

			panel.toggleFold();
			assertTrue(panel.foldOpen());
			assertEquals("1m", panel.minField().getText());
		});
		assertTrue(panel.describe(), panel.describe().contains("\"bandOn\":true,\"foldOpen\":true"));
	}

	// ---- addendum AA: the fold stands open, and remembers

	/**
	 * AA1: with nothing stored the fold is OPEN before anything is clicked - the chip strip and the two fields
	 * under the control row, which is the whole of the user's request ("can you by default have 'All items'
	 * expanded too so the user can see the quick price presets on the main tab?"). Construction writes nothing:
	 * the default is a reading of an empty config, not a choice anybody made.
	 */
	@Test
	public void theFoldStandsOpenOnAFreshInstall() throws Exception
	{
		prefs.storedFoldOpen = null;
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() ->
		{
			assertTrue("a fresh install opens on the fold", panel.foldOpen());
			assertEquals(Arrays.asList(panel.hero(), panel.controlRow(), panel.fold()),
				Arrays.asList(panel.header().getComponents()));
			assertSame("directly under the control row", panel.fold(), panel.header().getComponent(2));
			assertTrue("construction writes nothing back", prefs.foldSaves.isEmpty());
		});
		assertTrue(panel.describe(), panel.describe().contains("\"foldOpen\":true"));
	}

	/**
	 * AA1: and a STORED state wins over that default, both ways - the fold a reader shut is shut when they come
	 * back, which is the half of "open by default" that makes it a default rather than a rule.
	 */
	@Test
	public void aStoredFoldStateIsWhatTheSidebarOpensOn() throws Exception
	{
		prefs.storedFoldOpen = Boolean.FALSE;
		build();
		onEdt(() -> assertFalse("stored FALSE is not 'nothing stored'", panel.foldOpen()));

		// A second sidebar over the same memory, as a relaunch builds one.
		prefs.storedFoldOpen = Boolean.TRUE;
		final AtomicReference<BankPriceMovementPanel> relaunched = new AtomicReference<>();
		onEdt(() -> relaunched.set(new BankPriceMovementPanel(itemManager, service, prefs)));
		try
		{
			onEdt(() -> assertTrue("stored TRUE opens it", relaunched.get().foldOpen()));
		}
		finally
		{
			onEdt(() -> relaunched.get().stop());
		}
		assertTrue("neither launch wrote anything", prefs.foldSaves.isEmpty());
	}

	/**
	 * AA1: the band button's press is the gesture that is REMEMBERED - it writes the state it left the fold in
	 * through the prefs seam, both ways, and writes nothing when the fold was already in that state (the round
	 * trip {@code setOptions} and {@code setPresets} make for the gear menu).
	 */
	@Test
	public void theBandButtonsPressWritesTheFoldsState() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() ->
		{
			assertFalse(panel.foldOpen());
			press(panel.bandTarget());
			assertEquals(Collections.singletonList(Boolean.TRUE), prefs.foldSaves);
			press(panel.bandTarget());
			assertEquals(Arrays.asList(Boolean.TRUE, Boolean.FALSE), prefs.foldSaves);

			panel.pressFold(false);
			assertEquals("the state already in force writes nothing", 2, prefs.foldSaves.size());
			panel.pressFold(true);
			assertEquals("...and the bridge's fold=on takes the button's own road",
				Arrays.asList(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE), prefs.foldSaves);
			assertTrue(panel.foldOpen());
			assertTrue("nothing about the fold is a filter change", prefs.saves.isEmpty());
		});
		verify(service, never()).setFilter(any());
	}

	/**
	 * AA1: the settings page's road folds the sidebar and writes NOTHING back - the config it came from already
	 * holds the value, and a write here is the loop the {@code updating} guard exists to prevent. The bridge's
	 * {@code min=} opens it the same silent way: that is a script pointing at a control, not a reader choosing to
	 * keep it open.
	 */
	@Test
	public void theSettingsRoadFoldsTheSidebarWithoutWritingItBack() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() ->
		{
			panel.setFoldOpen(true);
			assertTrue(panel.foldOpen());
			assertSame(panel.fold(), panel.header().getComponent(2));
			panel.setFoldOpen(false);
			assertFalse(panel.foldOpen());
			assertEquals("the fold is removed, not hidden", 2, panel.header().getComponentCount());
			assertTrue("the road never writes", prefs.foldSaves.isEmpty());

			assertTrue(panel.applyMin("1m"));
			assertTrue("min= still opens the fold", panel.foldOpen());
			assertTrue("...and still says nothing about it to the config", prefs.foldSaves.isEmpty());
		});
	}

	/**
	 * AA1: what opens by itself is the fold addendum Z left behind, to the component - the chip strip over the
	 * field row, the four chips on the default presets with "All" lit, both fields on their placeholders and
	 * neither red, and "All items" still on the band button.
	 */
	@Test
	public void theFoldThatOpensByItselfIsTheFoldAddendumZDrew() throws Exception
	{
		prefs.storedFoldOpen = null;
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() ->
		{
			assertEquals("the chip strip over the field row", 2, panel.fold().getComponentCount());
			assertSame("the chips first", panel.presetCell(0).getParent(), panel.fold().getComponent(0));
			assertSame("the fields under them", panel.minField().getParent(), panel.fold().getComponent(1));
			assertEquals(Arrays.asList("All", "100k+", "1m+", "10m+"),
				Arrays.asList(panel.presetCell(0).getText(), panel.presetCell(1).getText(),
					panel.presetCell(2).getText(), panel.presetCell(3).getText()));
			assertLit("All lit on a fresh filter", 0);
			assertTrue(SwingUtilities.isDescendingFrom(panel.clearBoundsLabel(), panel.fold()));
			assertTrue(panel.minField().placeholderShowing());
			assertTrue(panel.maxField().placeholderShowing());
			assertFalse(Widgets.isMarkedInvalid(panel.minField()));
			assertFalse(Widgets.isMarkedInvalid(panel.maxField()));
			assertEquals("All items", panel.bandTarget().getText());
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, panel.fold().getBackground());
		});
	}

	/**
	 * AA3: the renderer's fixture opens on the fold, and the CLOSED sidebar is still one argument away - so every
	 * picture taken between addenda N and W stays reproducible from this class.
	 */
	@Test
	public void theRendererDrawsTheOpenFoldAndCanStillDrawTheClosedOne() throws Exception
	{
		assertTrue("the fixture ships open", LookRenderer.FOLD_OPEN);
		assertEquals("the 900 px pictures are one size, fold or no fold - the fold eats their bare ground",
			LookRenderer.HEIGHT, LookRenderer.height(LookRenderer.GUIDE_ONLY, false));
		assertEquals(LookRenderer.HEIGHT, LookRenderer.height(LookRenderer.GUIDE_ONLY, true));
		assertEquals("the options picture comes back at the size addendum W took it",
			LookRenderer.OPTIONS_HEIGHT_CLOSED, LookRenderer.height(LookRenderer.OPTIONS, false));
		assertEquals("...and is the fold's height taller with it open",
			LookRenderer.OPTIONS_HEIGHT_CLOSED + LookRenderer.FOLD_HEIGHT,
			LookRenderer.height(LookRenderer.OPTIONS, true));
		assertEquals(LookRenderer.OPTIONS_HEIGHT, LookRenderer.height(LookRenderer.OPTIONS));
		onEdt(() ->
		{
			final BankPriceMovementPanel open = LookRenderer.build(HeroVisibility.ALL, LookRenderer.GUIDE_ONLY);
			try
			{
				assertTrue("the default is the shipped sidebar", open.foldOpen());
				assertSame(open.fold(), open.header().getComponent(2));
			}
			finally
			{
				open.stop();
			}

			final BankPriceMovementPanel closed = LookRenderer.build(HeroVisibility.ALL, LookRenderer.GUIDE_ONLY, false);
			try
			{
				assertFalse("...and false is the sidebar of addenda N to W", closed.foldOpen());
				assertEquals(2, closed.header().getComponentCount());
			}
			finally
			{
				closed.stop();
			}
		});
	}

	/**
	 * AA3, measured: the new picture is the old one with the fold's band inserted under the control row. Every
	 * pixel row above the fold is identical, every row below it is the old picture's row shifted down by exactly
	 * the fold's own height, and the band that took their place is not what was there - which is the rule the
	 * checker applies to the four acceptance shots, applied here to the one it is easiest to read.
	 */
	@Test
	public void theOpenFoldShiftsThePictureDownByItsOwnHeightAndChangesNothingElse() throws Exception
	{
		final BufferedImage closed = LookRenderer.render(HeroVisibility.ALL, LookRenderer.GUIDE_ONLY, false);
		final BufferedImage open = LookRenderer.render(HeroVisibility.ALL, LookRenderer.GUIDE_ONLY, true);
		assertEquals(closed.getWidth(), open.getWidth());
		assertEquals(closed.getHeight(), open.getHeight());

		final AtomicReference<Rectangle> where = new AtomicReference<>();
		onEdt(() ->
		{
			final BankPriceMovementPanel drawn = LookRenderer.build(HeroVisibility.ALL, LookRenderer.GUIDE_ONLY);
			try
			{
				drawn.setSize(LookRenderer.WIDTH, LookRenderer.HEIGHT);
				LookRenderer.layoutTree(drawn);
				where.set(SwingUtilities.convertRectangle(drawn.fold().getParent(), drawn.fold().getBounds(), drawn));
			}
			finally
			{
				drawn.stop();
			}
		});
		final int top = where.get().y;
		final int shift = where.get().height;
		assertEquals("the fold is the height the pictures are sized around", LookRenderer.FOLD_HEIGHT, shift);
		assertTrue("...and its top is inside the picture", top > 0 && top + shift < closed.getHeight());
		for (int y = 0; y < top; y++)
		{
			assertTrue("row " + y + ", above the fold, moved", sameRow(closed, open, y, y));
		}
		for (int y = top; y + shift < closed.getHeight(); y++)
		{
			assertTrue("row " + y + " is not the old row shifted by " + shift, sameRow(closed, open, y, y + shift));
		}
		assertFalse("the inserted band is not what was there", sameRow(closed, open, top, top));
	}

	/**
	 * AA3, the band itself: what the picture gained is the fold as it has always been drawn. The sidebar that
	 * OPENS on the fold is byte for byte the sidebar whose band button was pressed by hand, so the inserted band
	 * is not a new widget, a second layout or a differently sized fold - it is the one addendum Z left behind,
	 * arriving without the click.
	 */
	@Test
	public void theSidebarThatOpensOnTheFoldIsTheSidebarWithTheBandButtonPressed() throws Exception
	{
		final BufferedImage byDefault = LookRenderer.render(HeroVisibility.ALL, LookRenderer.GUIDE_ONLY, true);
		final AtomicReference<BufferedImage> byHand = new AtomicReference<>();
		onEdt(() ->
		{
			final BankPriceMovementPanel drawn = LookRenderer.build(HeroVisibility.ALL, LookRenderer.GUIDE_ONLY, false);
			try
			{
				assertFalse(drawn.foldOpen());
				drawn.toggleFold();
				byHand.set(LookRenderer.paint(drawn, LookRenderer.HEIGHT));
			}
			finally
			{
				drawn.stop();
			}
		});
		assertFalse("the fold opened by hand draws the same picture", differ(byDefault, byHand.get()));
	}

	/**
	 * AA3: every picture is drawn with room for its whole list under the OPEN fold. The header grew by
	 * {@link LookRenderer#FOLD_HEIGHT} and the list under it lost exactly that; a list that no longer fits brings
	 * a scroll bar, which narrows every row card and is the failure {@link LookRenderer#OPTIONS_HEIGHT} was sized
	 * to avoid in the first place (R5). Three of the four pictures had 106 px or more of bare ground under their
	 * last row and simply gave 57 of it up; the options picture had 7, which is why it alone is taller again.
	 */
	@Test
	public void everyPictureIsTallEnoughForItsListWithTheFoldOpen() throws Exception
	{
		assertFitsWithTheFoldOpen(HeroVisibility.ALL, LookRenderer.GUIDE_ONLY, "the ticker");
		assertFitsWithTheFoldOpen(HeroVisibility.NONE, LookRenderer.GUIDE_ONLY, "the hidden card");
		assertFitsWithTheFoldOpen(HeroVisibility.ALL, LookRenderer.OPTIONS, "the options picture");
		assertFitsWithTheFoldOpen(HeroVisibility.ALL, LookRenderer.LIVE, "the live picture");
	}

	/** Lays one fixture out at the size it is painted and asserts no scroll bar came with the open fold. */
	private static void assertFitsWithTheFoldOpen(HeroVisibility shown, ViewOptions options, String what)
		throws Exception
	{
		onEdt(() ->
		{
			final BankPriceMovementPanel drawn = LookRenderer.build(shown, options);
			try
			{
				drawn.setSize(LookRenderer.WIDTH, LookRenderer.height(options));
				LookRenderer.layoutTree(drawn);
				assertTrue(what + ": the fold is open", drawn.foldOpen());
				assertFalse(what + " grew a scroll bar", drawn.scrollPane().getVerticalScrollBar().isVisible());
			}
			finally
			{
				drawn.stop();
			}
		});
	}

	/** One pixel row of {@code a} against one of {@code b}. */
	private static boolean sameRow(BufferedImage a, BufferedImage b, int ya, int yb)
	{
		for (int x = 0; x < a.getWidth(); x++)
		{
			if (a.getRGB(x, ya) != b.getRGB(x, yb))
			{
				return false;
			}
		}
		return true;
	}

	/** N 3.5: presets light on their EXACT band and apply it in one change; the lit one is a no-op. */
	@Test
	public void presetsLightExactlyOnTheirBandAndApplyIt() throws Exception
	{
		buildWithHovers();
		onEdt(() ->
		{
			panel.toggleFold();
			assertEquals(Arrays.asList("All", "100k+", "1m+", "10m+"),
				Arrays.asList(panel.presetCell(0).getText(), panel.presetCell(1).getText(), panel.presetCell(2).getText(), panel.presetCell(3).getText()));
			for (int i = 0; i < 4; i++)
			{
				assertEquals(new Dimension(BankPriceMovementPanel.PRESET_WIDTH, BankPriceMovementPanel.PRESET_HEIGHT),
					panel.presetCell(i).getPreferredSize());
				assertNotNull(panel.presetCell(i).getToolTipText());
			}
			assertLit("All lit on a fresh filter", 0);

			press(panel.presetCell(2));
			assertEquals(RowFilter.DEFAULT.withGpMin(1_000_000L), panel.filter());
			assertEquals("the fields follow a preset", "1,000,000", panel.minField().getText());
			assertEquals("", panel.maxField().getText());
			assertLit("1m+ lit", 2);
			assertEquals(1, prefs.saves.size());

			press(panel.presetCell(2));
			assertEquals("the lit preset is a no-op", 1, prefs.saves.size());

			assertTrue(panel.applyMax("5m"));
			assertLit("a custom band lights nothing", -1);

			press(panel.presetCell(3));
			assertEquals(RowFilter.DEFAULT.withGpMin(10_000_000L), panel.filter());
			assertEquals("", panel.maxField().getText());
			assertLit("10m+ lit", 3);

			press(panel.presetCell(0));
			assertEquals(RowFilter.DEFAULT, panel.filter());
			assertEquals("", panel.minField().getText());
			assertLit("All lit", 0);
		});
		verify(service).setFilter(RowFilter.DEFAULT.withGpMin(1_000_000L));
		verify(service).setFilter(RowFilter.DEFAULT.withGpMin(10_000_000L));
		verify(service).setFilter(RowFilter.DEFAULT);
	}

	private void assertLit(String what, int expected)
	{
		for (int i = 0; i < 4; i++)
		{
			assertEquals(what + ": preset " + i, i == expected, Widgets.isLit(panel.presetCell(i)));
		}
	}

	/** N 3.5: the drawn "x" clears both bounds; grey at rest, orange under the mouse. */
	@Test
	public void theClearGlyphClearsBothBounds() throws Exception
	{
		build();
		onEdt(() ->
		{
			assertTrue(panel.applyMin("100k"));
			assertTrue(panel.applyMax("5m"));
			assertEquals(2, prefs.saves.size());
			assertTrue(sameIcon(Widgets.clearIcon(ColorScheme.LIGHT_GRAY_COLOR), panel.clearBoundsLabel().getIcon()));
			hover(panel.clearBoundsLabel(), true);
			assertTrue(sameIcon(Widgets.clearIcon(ColorScheme.BRAND_ORANGE), panel.clearBoundsLabel().getIcon()));
			hover(panel.clearBoundsLabel(), false);
			press(panel.clearBoundsLabel());
			assertEquals(RowFilter.DEFAULT, panel.filter());
			assertEquals("", panel.minField().getText());
			assertEquals("", panel.maxField().getText());
			assertLit("All lit again", 0);
			assertEquals(3, prefs.saves.size());
			press(panel.clearBoundsLabel());
			assertEquals("nothing to clear is not a change", 3, prefs.saves.size());
		});
	}

	// ---- addendum Z: the three price presets (Z1-Z3)

	/**
	 * Z2: the gear menu's last group - a rule under the last view switch, the row with its grey caption and
	 * three boxes, and, since AH, the hover switch under it. The switch that rule sits under is "Include
	 * inventory and worn gear" since addendum AO deleted "Show stack value on rows" from beneath it.
	 *
	 * <p>"Reset to default" left this group in AH2: the user, looking at the menu, wanted it "in the orange box
	 * alongside 'OK' ... just aligned left", so it is a button in the bottom row now and
	 * {@link #theBottomRowHoldsResetOnTheLeftAndOkOnTheRight} is what pins it. Its TEXT is still pinned here,
	 * because the words are addendum Z's and moving a control is no licence to reword it.
	 *
	 * <p>That the row is a plain {@link JPanel} and not a menu item is not a detail of taste. {@code PopupFactory}
	 * makes a heavy-weight popup's window FOCUSABLE exactly when the popup holds a child that is neither a
	 * {@link MenuElement} nor a {@link JSeparator}, and RuneLite forces every popup heavy-weight
	 * ({@code ClientUI.setupDefaults}), so this row is what lets the boxes take the keyboard at all - without it
	 * every keystroke meant for a band would reach the game instead.
	 */
	@Test
	public void theGearMenuEndsWithThePresetBoxesAndTheHoverSwitch() throws Exception
	{
		buildWithHovers();
		final JPopupMenu menu = panel.heroMenu();
		assertEquals("eight items, the preset row, the hover switch, the OK row and three separators", 14,
			menu.getComponentCount());
		assertTrue("a rule under the last switch", menu.getComponent(10) instanceof JSeparator);
		assertSame("then the row", panel.presetRow(), menu.getComponent(11));
		// AH: and in the space under it, where the user drew the box - the group's rule is the one above, so the
		// hover switch joins this last group rather than starting another. AH2 took "Reset to default" out from
		// between the two and put it in the bottom row.
		assertSame(panel.showHoverTextItem(), menu.getComponent(12));
		assertEquals(BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT, ((JMenuItem) menu.getComponent(12)).getText());
		assertSame("and the OK row under everything (AB2)", panel.okRow(), menu.getComponent(13));
		assertFalse("the row is no menu element - which is what makes the popup window focusable",
			panel.presetRow() instanceof MenuElement);
		assertEquals("the caption addendum AB line AB3 asks for, pinned", "Preset price ranges",
			BankPriceMovementPanel.PRESETS_TEXT);
		// AB3's words are shorter than Z7's, so its "if that fits nicely" holds a fortiori; measured rather than
		// promised, because a popup is as wide as its widest child and a caption that outgrew the longest sentence
		// in the menu would widen the whole thing.
		assertTrue("'" + BankPriceMovementPanel.PRESETS_TEXT + "' is no wider than the longest check item",
			textWidth(new JLabel(BankPriceMovementPanel.PRESETS_TEXT), Widgets.sans(12))
				<= textWidth(new JLabel(BankPriceMovementPanel.COUNT_CASH_TEXT), Widgets.sans(12)));
		assertEquals("the words addendum Z asks for, pinned", "Reset to default",
			BankPriceMovementPanel.RESET_PRESETS_TEXT);
		assertTrue(texts(panel.presetRow()).contains(BankPriceMovementPanel.PRESETS_TEXT));
		// AH2: the same text and the same line, on a button in the bottom row instead of an item of its own.
		assertEquals(BankPriceMovementPanel.RESET_PRESETS_TEXT, panel.resetPresetsButton().getText());
		assertEquals(BankPriceMovementPanel.RESET_PRESETS_TIP, panel.resetPresetsButton().getToolTipText());
		assertEquals(BankPriceMovementPanel.PRESETS_TIP, panel.presetRow().getToolTipText());
		for (int i = 0; i < BankPriceMovementPanel.BANDS; i++)
		{
			assertTrue("box " + i + " is in the row", SwingUtilities.isDescendingFrom(panel.presetField(i), panel.presetRow()));
			assertEquals(BankPriceMovementPanel.PRESET_FIELD_WIDTH, panel.presetField(i).getPreferredSize().width);
			assertNotNull("box " + i + " says what it is", panel.presetField(i).getToolTipText());
			assertFalse(Widgets.isMarkedInvalid(panel.presetField(i)));
		}
		assertEquals("the boxes open on the bands in force, in gp shorthand", Arrays.asList("100k", "1m", "10m"),
			presetTexts());
		assertEquals(BandPresets.DEFAULT, panel.presets());
		assertTrue("construction saves nothing", prefs.presetSaves.isEmpty());
	}

	/**
	 * Z6: opening the gear menu targets NO box - the caret a reader sees is one they asked for by clicking.
	 *
	 * <p>The mechanism is two facts about Swing and this test names both. A heavy-weight popup's window is
	 * focusable exactly because the row is in it (Z2), and a freshly shown focusable window gives its focus to the
	 * first focusable component in it - which was the first band's field, "already selected and cursor is blinking
	 * there" as the user found it, until the ROW became focusable and the open event started asking for it
	 * ({@link BankPriceMovementPanel#menuFocusTarget()}). The boxes stay ordinary text fields, so a click still
	 * focuses one and every way of committing is untouched.
	 *
	 * <p>Focus itself belongs to a shown window, which this suite has none of: what can be pinned here is the
	 * target the menu asks for, that the row can hold it and that the boxes can still take a click.
	 */
	@Test
	public void openingTheGearMenuPutsTheKeyboardOnTheRowAndNotInABox() throws Exception
	{
		build();
		assertTrue("the row can hold the focus, which a plain JPanel cannot", panel.presetRow().isFocusable());
		assertSame("and it is the target the open event asks for", panel.presetRow(), panel.menuFocusTarget());
		for (int i = 0; i < BankPriceMovementPanel.BANDS; i++)
		{
			assertTrue("box " + i + " is still a field a click can focus",
				panel.presetField(i).getTextField().isFocusable());
		}
		onEdt(() ->
		{
			type(0, "2m");
			gearMenuOpening();
		});
		// The request is posted with invokeLater - the window takes its initial focus AFTER the open event - so
		// the queue is drained before the question is asked.
		onEdt(() ->
		{
			for (int i = 0; i < BankPriceMovementPanel.BANDS; i++)
			{
				assertFalse("no caret in box " + i + " until it is clicked",
					panel.presetField(i).getTextField().isFocusOwner());
				assertFalse(panel.presetField(i).isFocusOwner());
			}
			assertEquals("and the open still re-prints the bands in force", Arrays.asList("100k", "1m", "10m"),
				presetTexts());
		});
	}

	/**
	 * Z2: Enter reads the THREE boxes together, sorts them, stores them through {@code savePresets} and re-prints
	 * the boxes ascending - and the fold's chips take the new bands at once (Z3). The filter is not touched: a
	 * preset is the one-tap way to set a band, not a band.
	 */
	@Test
	public void enterReadsTheThreeBoxesTogetherSortsThemAndSavesThem() throws Exception
	{
		build();
		onEdt(() ->
		{
			panel.toggleFold();
			type(0, "10m");
			type(1, "1m");
			type(2, "100k");
			enter(0);
			assertEquals("the same three, smallest first", BandPresets.of(100_000L, 1_000_000L, 10_000_000L),
				panel.presets());
			assertEquals("the boxes re-print in ascending order", Arrays.asList("100k", "1m", "10m"), presetTexts());
			for (int i = 0; i < BankPriceMovementPanel.BANDS; i++)
			{
				assertFalse("box " + i + " read", Widgets.isMarkedInvalid(panel.presetField(i)));
			}
			assertTrue("the same trio again is not a change", prefs.presetSaves.isEmpty());

			type(0, "1m");
			type(1, "10m");
			type(2, "100m");
			enter(2);
			final BandPresets big = BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L);
			assertEquals(big, panel.presets());
			assertEquals(Arrays.asList(big), prefs.presetSaves);
			assertEquals(Arrays.asList("1m", "10m", "100m"), presetTexts());
			assertEquals("the fold followed", Arrays.asList("All", "1m+", "10m+", "100m+"), cellTexts());
			assertEquals("the reader's own band is untouched", RowFilter.DEFAULT, panel.filter());
			assertTrue(prefs.saves.isEmpty());
			assertTrue("the view switches and the card are untouched", prefs.optionSaves.isEmpty());
			assertTrue(prefs.heroSaves.isEmpty());
		});
		verify(service, never()).setFilter(any());
	}

	/** Z2: the other two ways a box commits - leaving it, and closing the menu with the text still in it. */
	@Test
	public void leavingABoxCommitsItAndSoDoesClosingTheMenu() throws Exception
	{
		build();
		onEdt(() ->
		{
			type(0, "250k");
			loseFocus(panel.presetField(0).getTextField(), true);
			assertEquals("a temporary loss is the window going away, not the reader leaving the box",
				BandPresets.DEFAULT, panel.presets());
			loseFocus(panel.presetField(0).getTextField(), false);
			assertEquals(BandPresets.of(250_000L, 1_000_000L, 10_000_000L), panel.presets());
			assertEquals(1, prefs.presetSaves.size());

			// ...and the menu closing commits all three, however the reader left them.
			type(1, "2m");
			type(2, "20m");
			gearMenuClosing();
			assertEquals(BandPresets.of(250_000L, 2_000_000L, 20_000_000L), panel.presets());
			assertEquals(2, prefs.presetSaves.size());
			assertEquals(Arrays.asList("250k", "2m", "20m"), presetTexts());
			gearMenuClosing();
			assertEquals("nothing changed: nothing written", 2, prefs.presetSaves.size());
		});
	}

	/**
	 * Z2: the two ways a trio can be refused, each shown where it was made - a box that names no positive gp
	 * amount, and two boxes naming the same one. Nothing is saved while any box is red, so the presets in force
	 * stay and the fold never shows a half-typed trio. It is the live list's step 2, in order.
	 */
	@Test
	public void aRefusedBoxGoesRedAndSavesNothing() throws Exception
	{
		build();
		onEdt(() ->
		{
			panel.toggleFold();
			type(0, "1m");
			type(1, "10m");
			type(2, "100m");
			enter(0);
			final BandPresets big = BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L);
			assertEquals(Arrays.asList(big), prefs.presetSaves);

			type(0, "abc");
			enter(0);
			assertTrue("the box that cannot be read is the one that reddens", Widgets.isMarkedInvalid(panel.presetField(0)));
			assertFalse(Widgets.isMarkedInvalid(panel.presetField(1)));
			assertFalse(Widgets.isMarkedInvalid(panel.presetField(2)));
			assertEquals("the presets in force stay", big, panel.presets());
			assertEquals("the fold still reads the last good three", Arrays.asList("All", "1m+", "10m+", "100m+"), cellTexts());
			assertEquals(1, prefs.presetSaves.size());
			assertEquals("what did not read reaches the box as typed", "abc", panel.presetField(0).getText());

			type(0, "10m");
			enter(0);
			assertTrue("a duplicate reddens BOTH boxes that name it", Widgets.isMarkedInvalid(panel.presetField(0)));
			assertTrue(Widgets.isMarkedInvalid(panel.presetField(1)));
			assertFalse(Widgets.isMarkedInvalid(panel.presetField(2)));
			assertEquals(big, panel.presets());
			assertEquals(1, prefs.presetSaves.size());

			type(0, "500k");
			loseFocus(panel.presetField(0).getTextField(), false);
			assertEquals(BandPresets.of(500_000L, 10_000_000L, 100_000_000L), panel.presets());
			assertEquals(Arrays.asList("All", "500k+", "10m+", "100m+"), cellTexts());
			for (int i = 0; i < BankPriceMovementPanel.BANDS; i++)
			{
				assertFalse("the red is gone with the reason for it", Widgets.isMarkedInvalid(panel.presetField(i)));
			}
			assertEquals(2, prefs.presetSaves.size());

			// A band of nothing is not a band: "All" is already the fold's first chip.
			for (String nothing : new String[]{"", "0", "-5"})
			{
				type(1, nothing);
				enter(1);
				assertTrue("'" + nothing + "' is not a band", Widgets.isMarkedInvalid(panel.presetField(1)));
				assertEquals(2, prefs.presetSaves.size());
			}
			assertEquals(BandPresets.of(500_000L, 10_000_000L, 100_000_000L), panel.presets());

			// Opening the menu again asks the question afresh: the refused text goes, the bands in force come back.
			gearMenuOpening();
			assertFalse(Widgets.isMarkedInvalid(panel.presetField(1)));
			assertEquals(Arrays.asList("500k", "10m", "100m"), presetTexts());
			assertEquals(2, prefs.presetSaves.size());
		});
	}

	/** Z2: "Reset to default" puts 100k / 1m / 10m back in the boxes, on the chips and in the config. */
	@Test
	public void resetToDefaultPutsTheThreeDefaultBandsBack() throws Exception
	{
		build();
		onEdt(() ->
		{
			panel.toggleFold();
			panel.setPresets(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L));
			assertEquals(Arrays.asList("All", "1m+", "10m+", "100m+"), cellTexts());

			panel.resetPresetsButton().doClick(0);
			assertEquals(BandPresets.DEFAULT, panel.presets());
			assertEquals(Arrays.asList("100k", "1m", "10m"), presetTexts());
			assertEquals(Arrays.asList("All", "100k+", "1m+", "10m+"), cellTexts());
			assertEquals(2, prefs.presetSaves.size());
			assertEquals(BandPresets.DEFAULT, prefs.presetSaves.get(1));

			panel.resetPresetsButton().doClick(0);
			assertEquals("already there: nothing written", 2, prefs.presetSaves.size());
			// ...but it still clears a refused edit, which is the other thing a reader presses it for.
			type(2, "abc");
			enter(2);
			assertTrue(Widgets.isMarkedInvalid(panel.presetField(2)));
			panel.resetPresetsButton().doClick(0);
			assertFalse(Widgets.isMarkedInvalid(panel.presetField(2)));
			assertEquals(Arrays.asList("100k", "1m", "10m"), presetTexts());
			assertEquals(2, prefs.presetSaves.size());
		});
	}

	/**
	 * Z3: the fold's four chips are "All" and the three presets - their labels, their hovers and the band each one
	 * applies - and the LIT one can move without the reader's band moving, because the bands under it changed.
	 */
	@Test
	public void theFoldsChipsAreThePresetsAndPressingOneAppliesItsBand() throws Exception
	{
		buildWithHovers();
		onEdt(() ->
		{
			panel.toggleFold();
			press(panel.presetCell(2));
			assertEquals(RowFilter.DEFAULT.withGpMin(1_000_000L), panel.filter());
			assertLit("1m+ lit", 2);

			panel.applyPresets(BandPresets.of(500_000L, 10_000_000L, 100_000_000L));
			assertEquals(Arrays.asList("All", "500k+", "10m+", "100m+"), cellTexts());
			assertEquals("Every item, whatever its price", panel.presetCell(0).getToolTipText());
			assertEquals("Items priced 500k and up", panel.presetCell(1).getToolTipText());
			assertEquals("Items priced 100m and up", panel.presetCell(3).getToolTipText());
			assertEquals("the reader's band is untouched by a preset change", 1_000_000L, panel.filter().gpMin());
			assertLit("1m is no longer one of the three", -1);

			press(panel.presetCell(3));
			assertEquals("the chip applies the band its PLACE holds now", RowFilter.DEFAULT.withGpMin(100_000_000L),
				panel.filter());
			assertLit("100m+ lit", 3);
			assertEquals("the fields follow a preset", "100,000,000", panel.minField().getText());
			assertEquals("", panel.maxField().getText());

			press(panel.presetCell(0));
			assertEquals(RowFilter.DEFAULT, panel.filter());
			assertLit("All lit", 0);
		});
		verify(service).setFilter(RowFilter.DEFAULT.withGpMin(100_000_000L));
	}

	/** Z1: applyPresets is the config's road - it repaints the boxes and the chips, and saves nothing. */
	@Test
	public void applyPresetsRepaintsWithoutSavingAndNullReadsAsTheDefault() throws Exception
	{
		build();
		onEdt(() ->
		{
			type(0, "abc");
			enter(0);
			assertTrue(Widgets.isMarkedInvalid(panel.presetField(0)));

			panel.applyPresets(BandPresets.of(2_000_000L, 20_000_000L, 200_000_000L));
			assertEquals(BandPresets.of(2_000_000L, 20_000_000L, 200_000_000L), panel.presets());
			assertEquals(Arrays.asList("2m", "20m", "200m"), presetTexts());
			assertFalse("the config's answer clears a refused edit", Widgets.isMarkedInvalid(panel.presetField(0)));
			assertEquals(Arrays.asList("All", "2m+", "20m+", "200m+"), cellTexts());
			assertTrue("no save loop", prefs.presetSaves.isEmpty());
			assertTrue(prefs.saves.isEmpty());

			panel.applyPresets(null);
			assertEquals("null reads as 100k / 1m / 10m", BandPresets.DEFAULT, panel.presets());
			assertEquals(Arrays.asList("100k", "1m", "10m"), presetTexts());
			assertTrue(prefs.presetSaves.isEmpty());

			panel.setPresets(null);
			assertEquals("already there: nothing written", 0, prefs.presetSaves.size());
			panel.setPresets(BandPresets.of(100_000L, 1_000_000L, 11_000_000L));
			assertEquals(1, prefs.presetSaves.size());
		});
		verify(service, never()).setFilter(any());
	}

	/** Z1: the three come from the config through the prefs seam, and a fresh profile opens on the default. */
	@Test
	public void theInitialPresetsComeFromThePrefs() throws Exception
	{
		prefs.storedPresets = BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L);
		build();
		onEdt(() ->
		{
			assertEquals(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L), panel.presets());
			assertEquals(Arrays.asList("1m", "10m", "100m"), presetTexts());
			assertEquals(Arrays.asList("All", "1m+", "10m+", "100m+"), cellTexts());
			assertTrue("construction saves nothing", prefs.presetSaves.isEmpty());
		});

		// A fresh profile - and a stored string that named anything but three distinct positive amounts, which
		// reaches this seam as null - opens on 100k / 1m / 10m.
		service = mock(PriceService.class);
		prefs = new RecordingPrefs();
		build();
		onEdt(() ->
		{
			assertEquals(BandPresets.DEFAULT, panel.presets());
			assertEquals(Arrays.asList("All", "100k+", "1m+", "10m+"), cellTexts());
		});
	}

	/** Z4: the three bands as numbers and the four chip texts as drawn, both in {@code describe()}. */
	@Test
	public void describeCarriesTheThreeBandsAndTheFourChipTexts() throws Exception
	{
		build();
		assertTrue(panel.describe(), panel.describe().contains(
			",\"presets\":[100000,1000000,10000000],\"presetLabels\":[\"All\",\"100k+\",\"1m+\",\"10m+\"]"));
		onEdt(() -> panel.setPresets(BandPresets.of(100_000_000L, 1_000_000L, 10_000_000L)));
		assertTrue(panel.describe(), panel.describe().contains(
			",\"presets\":[1000000,10000000,100000000],\"presetLabels\":[\"All\",\"1m+\",\"10m+\",\"100m+\"]"));
		assertTrue("the fold's own keys are unmoved", panel.describe().contains("\"bandOn\":false,\"foldOpen\":false"));
	}

	/**
	 * Z3 and Z5: with the DEFAULT presets the fold is the fold addendum W left behind, to the character - the four
	 * labels, their four hovers and their pinned cells. It is the promise the four acceptance pictures rest on,
	 * since the fixtures draw the fold closed and never open the menu at all.
	 */
	@Test
	public void theDefaultPresetsDrawExactlyTheFoldOfAddendumW() throws Exception
	{
		buildWithHovers();
		onEdt(() ->
		{
			panel.toggleFold();
			assertEquals(Arrays.asList("All", "100k+", "1m+", "10m+"), cellTexts());
			assertEquals(Arrays.asList("Every item, whatever its price", "Items priced 100k and up",
				"Items priced 1m and up", "Items priced 10m and up"),
				Arrays.asList(panel.presetCell(0).getToolTipText(), panel.presetCell(1).getToolTipText(),
					panel.presetCell(2).getToolTipText(), panel.presetCell(3).getToolTipText()));
			for (int i = 0; i < BankPriceMovementPanel.PRESET_COUNT; i++)
			{
				assertEquals(new Dimension(BankPriceMovementPanel.PRESET_WIDTH, BankPriceMovementPanel.PRESET_HEIGHT),
					panel.presetCell(i).getPreferredSize());
			}
			assertEquals("the fold is still the chip bar over the field bar", 2, panel.fold().getComponentCount());
			assertSame("the chips first", panel.presetCell(0).getParent(), panel.fold().getComponent(0));
			assertSame("the fields under them", panel.minField().getParent(), panel.fold().getComponent(1));
		});
	}

	/** The three preset boxes' texts, smallest first. */
	private List<String> presetTexts()
	{
		final List<String> out = new ArrayList<>(BankPriceMovementPanel.BANDS);
		for (int i = 0; i < BankPriceMovementPanel.BANDS; i++)
		{
			out.add(panel.presetField(i).getText());
		}
		return out;
	}

	/** The fold's four chip texts, in order. */
	private List<String> cellTexts()
	{
		final List<String> out = new ArrayList<>(BankPriceMovementPanel.PRESET_COUNT);
		for (int i = 0; i < BankPriceMovementPanel.PRESET_COUNT; i++)
		{
			out.add(panel.presetCell(i).getText());
		}
		return out;
	}

	private void type(int box, String text)
	{
		panel.presetField(box).setText(text);
	}

	/** Enter in one box - which reads all three (Z2). */
	private void enter(int box)
	{
		panel.presetField(box).getTextField().postActionEvent();
	}

	/** Focus leaving a field, permanently or because the window went away. */
	private static void loseFocus(JTextField tf, boolean temporary)
	{
		final FocusEvent e = new FocusEvent(tf, FocusEvent.FOCUS_LOST, temporary);
		for (FocusListener l : tf.getFocusListeners())
		{
			l.focusLost(e);
		}
	}

	/** The gear menu going away, as Swing tells its listeners (Z2). */
	private void gearMenuClosing()
	{
		final PopupMenuEvent e = new PopupMenuEvent(panel.heroMenu());
		for (PopupMenuListener l : panel.heroMenu().getPopupMenuListeners())
		{
			l.popupMenuWillBecomeInvisible(e);
		}
	}

	/** The gear menu being shown - the panel cannot place a real one off-screen (Q1). */
	private void gearMenuOpening()
	{
		final PopupMenuEvent e = new PopupMenuEvent(panel.heroMenu());
		for (PopupMenuListener l : panel.heroMenu().getPopupMenuListeners())
		{
			l.popupMenuWillBecomeVisible(e);
		}
	}

	// ---- addendum AB: the gear toggles (AB1) and the menu's OK button (AB2)

	/**
	 * AB1: a press on the gear while its menu stands closes the menu and opens nothing - and one after the guard
	 * opens it again.
	 *
	 * <p>The mechanism is one Swing fact, and it is why the rule is written as a CLOCK rather than as
	 * {@code if (menu.isVisible())}. RuneLite forces every popup heavy-weight ({@code ClientUI.setupDefaults}) and
	 * {@code BasicPopupMenuUI.MouseGrabber} cancels an open popup on any press OUTSIDE it - the gear is outside -
	 * before that press reaches the gear's own listener. So the gear can never see its menu open, and the loop the
	 * user found follows: "if we click the gear settings icon while its already open it closes, currently it just
	 * reopens on a loop". The panel records the close instead ({@code popupMenuWillBecomeInvisible}) and reads a
	 * press inside {@link BankPriceMovementPanel#GEAR_REOPEN_GUARD_MILLIS} of it as the close it really was.
	 *
	 * <p>What a press DECIDES is asked here, not what a popup shows: this suite has no window to show one in, which
	 * is the same reason Z6 is pinned through {@code menuFocusTarget()}. The panel's own clock is driven, so the
	 * boundary is exact rather than slept through.
	 */
	@Test
	public void aGearPressWithinTheGuardOfACloseOpensNothingAndOneAfterItOpens() throws Exception
	{
		build();
		final AtomicLong now = new AtomicLong(PRICES_AT);
		onEdt(() ->
		{
			panel.setClock(now::get);
			assertEquals("the guard addendum AB line AB1 asks for, pinned", 300L,
				BankPriceMovementPanel.GEAR_REOPEN_GUARD_MILLIS);
			assertTrue("nothing has closed yet, so the first press opens", panel.gearPressOpens());
			press(panel.gearLabel());
			assertFalse("nothing is shown from an off-screen gear either way (Q1)", panel.heroMenu().isVisible());

			// The press that closes it: the grabber's cancel reaches the menu first, and this is what the panel
			// hears of it.
			gearMenuClosing();
			for (long after : new long[]{0L, 100L, BankPriceMovementPanel.GEAR_REOPEN_GUARD_MILLIS - 1})
			{
				now.set(PRICES_AT + after);
				assertFalse("a press " + after + " ms after the close is that close, and opens nothing",
					panel.gearPressOpens());
				press(panel.gearLabel());
				assertFalse(panel.heroMenu().isVisible());
			}

			now.set(PRICES_AT + BankPriceMovementPanel.GEAR_REOPEN_GUARD_MILLIS);
			assertTrue("the guard is over at exactly 300 ms", panel.gearPressOpens());
			now.set(PRICES_AT + 400L);
			assertTrue("and a click after the pause opens the menu as it always did", panel.gearPressOpens());

			// A second close starts a second guard: the rule is about the LAST close, not the first.
			gearMenuClosing();
			now.set(PRICES_AT + 500L);
			assertFalse("100 ms after the second close", panel.gearPressOpens());
			now.set(PRICES_AT + 800L);
			assertTrue(panel.gearPressOpens());
		});
	}

	/**
	 * AB2: the menu's last row is a right-aligned "OK" button under "Reset to default", and pressing it commits the
	 * boxes and takes the menu down.
	 *
	 * <p>The user asked for "a 'save' or 'ok' button at the bottom right of the gear settings menu that will close
	 * the settings box as well", and both halves are pinned: WHERE it is (last in the menu, and at the right end of
	 * its own row, which is measured rather than asserted from the glue alone) and WHAT it does. It reads "OK" and
	 * not "Save" because everything in this menu has already saved itself by the time it is pressed - the commit
	 * here is the same one the close makes (Z2), spelled out so that the button's promise holds however Swing
	 * delivers the hide.
	 */
	@Test
	public void theOkButtonSitsAtTheBottomRightAndCommitsThenClosesTheMenu() throws Exception
	{
		buildWithHovers();
		final JPopupMenu menu = panel.heroMenu();
		onEdt(() ->
		{
			assertSame("the last thing in the menu", panel.okRow(), menu.getComponent(menu.getComponentCount() - 1));
			// AH slid one item in between: the row is still the last thing in the menu, and what it now sits
			// directly under is the hover switch. "Reset to default" is no longer above it at all - AH2 brought
			// it INTO this row, which is what theBottomRowHoldsResetOnTheLeftAndOkOnTheRight pins.
			assertEquals("directly under 'Show hover text'", BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT,
				((JMenuItem) menu.getComponent(menu.getComponentCount() - 2)).getText());
			assertSame("...with the preset row above that", panel.presetRow(),
				menu.getComponent(menu.getComponentCount() - 3));
			assertFalse("a row and not a menu element, like the preset row above it",
				panel.okRow() instanceof MenuElement);
			assertEquals("the word addendum AB line AB2 asks for, pinned", "OK", BankPriceMovementPanel.OK_TEXT);
			assertEquals(BankPriceMovementPanel.OK_TEXT, panel.okButton().getText());
			assertEquals(BankPriceMovementPanel.OK_TIP, panel.okButton().getToolTipText());
			assertEquals("in the menu's own face, not the button default", Widgets.sans(12),
				panel.okButton().getFont());
			assertEquals("and no ButtonUI may stretch it", panel.okButton().getPreferredSize(),
				panel.okButton().getMaximumSize());

			// Right-aligned, measured: the glue takes the row's spare width and the button ends on its edge.
			final JPanel row = panel.okRow();
			assertEquals("reset, a glue, then the button (AH2)", 3, row.getComponentCount());
			assertSame(panel.okButton(), row.getComponent(2));
			assertEquals("the glue asks for no width of its own", 0, row.getComponent(1).getPreferredSize().width);
			final int width = 200;
			row.setSize(width, row.getPreferredSize().height);
			row.doLayout();
			final Insets insets = row.getInsets();
			assertEquals("the button ends at the row's right edge", width - insets.right,
				panel.okButton().getX() + panel.okButton().getWidth());
			assertTrue("with all the room to its LEFT", panel.okButton().getX() > width / 2);

			// The press: the typed trio is committed, exactly as a close commits it...
			panel.toggleFold();
			type(0, "1m");
			type(1, "10m");
			type(2, "100m");
			panel.okButton().doClick(0);
			final BandPresets big = BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L);
			assertEquals(big, panel.presets());
			assertEquals(Arrays.asList(big), prefs.presetSaves);
			assertEquals("the fold followed", Arrays.asList("All", "1m+", "10m+", "100m+"), cellTexts());
			// ...and the menu is down. (It was never up: a JPopupMenu that is not visible ignores setVisible(false),
			// which is also why the commit above is the button's own and not the close's.)
			assertFalse(menu.isVisible());
			assertTrue("the reader's own band is untouched by an OK", prefs.saves.isEmpty());

			// A refused trio is still refused, in red, and the menu still closes: the question is asked afresh on
			// the next open.
			type(1, "abc");
			panel.okButton().doClick(0);
			assertTrue(Widgets.isMarkedInvalid(panel.presetField(1)));
			assertEquals("nothing was saved over the last good three", big, panel.presets());
			assertEquals(1, prefs.presetSaves.size());
			assertFalse(menu.isVisible());
			gearMenuOpening();
			assertFalse("the red went with the reason for it", Widgets.isMarkedInvalid(panel.presetField(1)));
			assertEquals(Arrays.asList("1m", "10m", "100m"), presetTexts());
		});
	}

	/**
	 * AH2: the gear menu's bottom row holds BOTH buttons - "Reset to default" hard left, "OK" hard right.
	 *
	 * <p>The user, looking at the menu they had just asked for: "i changed my mind, i would like the 'reset to
	 * default' button to be in the orange box alongside 'OK' ... just aligned left, OK button align right as it
	 * is currently is". So the way back to the default bands stops being a menu item of its own - it was the
	 * only entry in the gear menu that was not a switch - and becomes a button in the row that closes the menu,
	 * which is where a reader looks for a command rather than a setting.
	 *
	 * <p>Three things are pinned and all three are load-bearing. WHERE: first child, glue, last child, which is
	 * what BoxLayout turns into "one pinned left, one pinned right" however wide the popup is, and it is
	 * MEASURED here rather than trusted to the glue. WHAT IT STILL IS: the same words, the same line and the
	 * same action addendum Z gave it - moving a control is no licence to reword or re-aim it. And that it obeys
	 * the hover switch like everything else (AH3), since a button that kept its tooltip would be exactly the
	 * kind of thing the user found still talking.
	 */
	@Test
	public void theBottomRowHoldsResetOnTheLeftAndOkOnTheRight() throws Exception
	{
		buildWithHovers();
		final JPopupMenu menu = panel.heroMenu();
		onEdt(() ->
		{
			final JPanel row = panel.okRow();
			assertSame("the row is the last thing in the menu (AB2)", row,
				menu.getComponent(menu.getComponentCount() - 1));
			assertEquals("reset, a glue, then OK", 3, row.getComponentCount());
			assertSame("'Reset to default' first", panel.resetPresetsButton(), row.getComponent(0));
			assertEquals("the glue asks for no width of its own", 0, row.getComponent(1).getPreferredSize().width);
			assertSame("and OK last", panel.okButton(), row.getComponent(2));
			assertFalse("AH2: it is no longer an entry in the menu",
				itemTexts(menu).contains(BankPriceMovementPanel.RESET_PRESETS_TEXT));

			// Measured, not assumed: the glue between them takes the row's spare width, so one button ends up
			// against the left inset and the other against the right one at any width the popup happens to be.
			final int width = 200;
			row.setSize(width, row.getPreferredSize().height);
			row.doLayout();
			final Insets insets = row.getInsets();
			assertEquals("reset starts at the row's left edge", insets.left, panel.resetPresetsButton().getX());
			assertEquals("OK ends at the row's right edge", width - insets.right,
				panel.okButton().getX() + panel.okButton().getWidth());
			assertTrue("with the room between them",
				panel.resetPresetsButton().getX() + panel.resetPresetsButton().getWidth() < panel.okButton().getX());
			assertEquals("and no ButtonUI may stretch it", panel.resetPresetsButton().getPreferredSize(),
				panel.resetPresetsButton().getMaximumSize());
			assertEquals("in the menu's own face, like OK beside it", Widgets.sans(12),
				panel.resetPresetsButton().getFont());

			// The same words and the same line addendum Z gave the item it replaces.
			assertEquals("Reset to default", BankPriceMovementPanel.RESET_PRESETS_TEXT);
			assertEquals(BankPriceMovementPanel.RESET_PRESETS_TEXT, panel.resetPresetsButton().getText());
			assertEquals(BankPriceMovementPanel.RESET_PRESETS_TIP,
				panel.resetPresetsButton().getToolTipText());
			assertEquals(BankPriceMovementPanel.OK_TIP, panel.okButton().getToolTipText());

			// ...and the same action: 100k / 1m / 10m back in the boxes, on the chips and in the config.
			panel.toggleFold();
			panel.setPresets(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L));
			assertEquals(Arrays.asList("All", "1m+", "10m+", "100m+"), cellTexts());
			panel.resetPresetsButton().doClick(0);
			assertEquals(BandPresets.DEFAULT, panel.presets());
			assertEquals(Arrays.asList("100k", "1m", "10m"), presetTexts());
			assertEquals(Arrays.asList("All", "100k+", "1m+", "10m+"), cellTexts());
			assertEquals("one write for the trio it replaced, one for the default it put back", 2,
				prefs.presetSaves.size());
		});

		// AH3: both buttons go quiet with everything else, and come back with it.
		onEdt(() ->
		{
			panel.applyOptions(ViewOptions.DEFAULT);
			assertNull("the reset button", panel.resetPresetsButton().getToolTipText());
			assertNull("the OK button", panel.okButton().getToolTipText());
			panel.applyOptions(HOVERS_ON);
			assertEquals(BankPriceMovementPanel.RESET_PRESETS_TIP,
				panel.resetPresetsButton().getToolTipText());
			assertEquals(BankPriceMovementPanel.OK_TIP, panel.okButton().getToolTipText());
		});
	}

	/** N 3.5: the bridge's min= opens the fold; an invalid bound marks the field red and changes nothing. */
	@Test
	public void applyMinOpensTheFoldAndAnInvalidBoundTurnsTheFieldRed() throws Exception
	{
		build();
		onEdt(() -> panel.applyMin("100k"));
		assertTrue("min= opens the fold so a shot shows what happened", panel.foldOpen());
		onEdt(() -> assertFalse(panel.applyMin("abc")));
		assertTrue(Widgets.isMarkedInvalid(panel.minField()));
		assertEquals("abc", panel.minField().getText());
		assertEquals("the filter is unchanged", 100_000L, panel.filter().gpMin());
		assertEquals(1, prefs.saves.size());
		verify(service, times(1)).setFilter(any());
		assertTrue(panel.describe(), panel.describe().contains("\"minInvalid\":true"));

		onEdt(() -> assertFalse(panel.applyMin("-5")));
		assertTrue("a negative is invalid too", Widgets.isMarkedInvalid(panel.minField()));

		onEdt(() -> assertTrue(panel.applyMin("")));
		assertFalse("an empty field is no bound, and clears the red", Widgets.isMarkedInvalid(panel.minField()));
		assertEquals(0L, panel.filter().gpMin());
		assertEquals(2, prefs.saves.size());
		assertTrue(panel.minField().placeholderShowing());
	}

	@Test
	public void minAndMaxParseStackSuffixesAndKeepTheTypedText() throws Exception
	{
		build();
		onEdt(() -> assertTrue(panel.applyMin("100k")));
		assertEquals(100_000L, panel.filter().gpMin());
		assertEquals("100k", panel.minField().getText());
		assertFalse(Widgets.isMarkedInvalid(panel.minField()));
		assertFalse(panel.minField().placeholderShowing());

		onEdt(() -> assertTrue(panel.applyMax("1.5m")));
		assertEquals(1_500_000L, panel.filter().gpMax());
		assertEquals("1.5m", panel.maxField().getText());
		assertEquals(Arrays.asList(RowFilter.DEFAULT.withGpMin(100_000L), RowFilter.DEFAULT.withGpMin(100_000L).withGpMax(1_500_000L)),
			prefs.saves);
		verify(service).setFilter(RowFilter.DEFAULT.withGpMin(100_000L).withGpMax(1_500_000L));

		onEdt(() -> assertTrue(panel.applyMax(" 2,000,000 ")));
		assertEquals("trimmed, commas accepted", 2_000_000L, panel.filter().gpMax());
	}

	/**
	 * P3: a band of NOTHING is spelled as nothing. A "0" - typed, or pressed in by the dev bridge - parses to a
	 * bound of nothing, and the field is re-rendered from the filter that produced it, so the placeholder comes
	 * back instead of a literal 0 sitting where "max gp" belongs (which is what the operator photographed on
	 * 2026-09-10). Any other value keeps the spelling the user chose, and a text that does not parse is left
	 * exactly as typed.
	 */
	@Test
	public void aZeroBoundComesBackAsAnEmptyFieldAndEverythingElseKeepsItsSpelling() throws Exception
	{
		build();
		onEdt(() ->
		{
			assertTrue(panel.applyMax("1.5m"));
			assertEquals("the spelling the user chose", "1.5m", panel.maxField().getText());
			assertEquals(1_500_000L, panel.filter().gpMax());

			assertTrue(panel.applyMax("0"));
			assertEquals("a zero is nothing, and nothing is an empty field", "", panel.maxField().getText());
			assertTrue("...so the placeholder is what is on screen", panel.maxField().placeholderShowing());
			assertEquals(0L, panel.filter().gpMax());

			// The same by the other road in, and for every spelling of nothing: Enter on the field itself.
			final JTextField tf = panel.minField().getTextField();
			tf.setText("0k");
			tf.postActionEvent();
			assertEquals("", panel.minField().getText());
			assertEquals(0L, panel.filter().gpMin());

			assertFalse(panel.applyMax("abc"));
			assertEquals("what did not parse reaches the field as typed", "abc", panel.maxField().getText());
			assertTrue(Widgets.isMarkedInvalid(panel.maxField()));
			assertEquals("...and the filter is untouched", 0L, panel.filter().gpMax());
		});
	}

	@Test
	public void enterAndFocusLostApplyTheField() throws Exception
	{
		build();
		onEdt(() ->
		{
			final JTextField tf = panel.minField().getTextField();
			tf.setText("5k");
			tf.postActionEvent();
			assertEquals("Enter applies", 5_000L, panel.filter().gpMin());

			tf.setText("6k");
			for (FocusListener l : tf.getFocusListeners())
			{
				l.focusLost(new FocusEvent(tf, FocusEvent.FOCUS_LOST, true));
			}
			assertEquals("a temporary focus loss (the window) does not apply", 5_000L, panel.filter().gpMin());
			for (FocusListener l : tf.getFocusListeners())
			{
				l.focusLost(new FocusEvent(tf, FocusEvent.FOCUS_LOST, false));
			}
			assertEquals("focus lost applies", 6_000L, panel.filter().gpMin());
		});
		assertEquals(2, prefs.saves.size());
	}

	@Test
	public void aClickNeverRewritesTheFields() throws Exception
	{
		build();
		onEdt(() -> panel.applyMin("100k"));
		onEdt(() -> panel.minField().setText("typing"));
		onEdt(() -> press(panel.windowChip(MovementWindow.D30)));
		assertEquals("typing", panel.minField().getText());
		assertEquals(100_000L, panel.filter().gpMin());
		onEdt(() -> panel.setSort(SortMode.UNIT_PRICE, false));
		assertEquals("typing", panel.minField().getText());
	}

	// ---- applyFilter (ConfigChanged)

	@Test
	public void applyFilterRepaintsTheWidgetsWithoutSaving() throws Exception
	{
		build();
		final RowFilter fromConfig = new RowFilter(0L, 2_000_000L, SortMode.UNIT_PRICE, false, MovementWindow.D7);
		onEdt(() -> panel.applyFilter(fromConfig));
		assertEquals(fromConfig, panel.filter());
		assertTrue(Widgets.isLit(panel.windowChip(MovementWindow.D7)));
		assertFalse(Widgets.isLit(panel.windowChip(MovementWindow.D1)));
		assertEquals("Item price", panel.sortButton().getText());
		assertTrue("the config's direction is the arrow (W2)",
			sameIcon(Widgets.triangle(false), panel.sortButton().getIcon()));
		assertEquals("", panel.minField().getText());
		assertEquals("2,000,000", panel.maxField().getText());
		assertEquals("the band button states the config's band", "up to 2m", panel.bandTarget().getText());
		assertTrue("no save loop", prefs.saves.isEmpty());
		verify(service, never()).setFilter(any());

		onEdt(() -> panel.applyFilter(null));
		assertEquals("null reads as the default", RowFilter.DEFAULT, panel.filter());
		assertEquals("", panel.maxField().getText());
		assertEquals("Percent change", panel.sortButton().getText());
		assertTrue(sameIcon(Widgets.triangle(true), panel.sortButton().getIcon()));
		assertEquals("All items", panel.bandTarget().getText());
		assertTrue(prefs.saves.isEmpty());
	}

	@Test
	public void applyFilterKeepsTheUsersTextWhenItMeansTheSameValue() throws Exception
	{
		build();
		onEdt(() -> panel.applyMin("100k"));
		onEdt(() -> panel.applyFilter(RowFilter.DEFAULT.withGpMin(100_000L)));
		assertEquals("100k", panel.minField().getText());

		onEdt(() -> panel.applyMin("abc"));
		onEdt(() -> panel.applyFilter(RowFilter.DEFAULT.withGpMin(100_000L)));
		assertEquals("an invalid text is replaced by the config's value", "100,000", panel.minField().getText());
		assertFalse(Widgets.isMarkedInvalid(panel.minField()));

		onEdt(() -> panel.applyFilter(RowFilter.DEFAULT.withGpMin(250_000L)));
		assertEquals("250,000", panel.minField().getText());
	}

	// ---- the problem row

	/** N 3.6: added while there is a problem, red for failures and the cooldown, grey for L11's "No 180d history". */
	@Test
	public void problemRowIsRedForFailuresGreyForNoHistoryAndRemovedWhenClear() throws Exception
	{
		buildWithHovers();
		publish(rows(1), listed(1, 1));
		assertFalse(panel.problemShowing());
		assertEquals("", panel.problemLabel().getText());

		publish(rows(1), listedWithProblem(COOLDOWN, MovementWindow.D1, THEN_DAY));
		assertTrue(panel.problemShowing());
		assertSame("the last row of the header", panel.problemLabel(), panel.header().getComponent(panel.header().getComponentCount() - 1));
		assertEquals(Arrays.asList(panel.hero(), panel.controlRow(), panel.problemLabel()), Arrays.asList(panel.header().getComponents()));
		assertEquals(COOLDOWN, panel.problemLabel().getText());
		assertEquals("the whole sentence is the tooltip", COOLDOWN, panel.problemLabel().getToolTipText());
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, panel.problemLabel().getForeground());
		assertEquals(12f, panel.problemLabel().getFont().getSize2D(), 0f);
		assertTrue(panel.describe(), panel.describe().contains("\"problemText\":\"" + COOLDOWN + "\",\"problemRed\":true"));

		publish(rows(1), listedWithProblem(UNAVAILABLE, MovementWindow.D1, THEN_DAY));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, panel.problemLabel().getForeground());
		// The longest sentence measures 213 px at 12 px (N section 3 §2, T6) and the row keeps 6 px each side, so it
		// is fitted - cut with "..." and whole in the tooltip - exactly as that measurement says.
		assertFitted(panel.problemLabel(), UNAVAILABLE);

		// B053 / B106: the FIRST fetch being in flight is progress, not a failure - and on a fresh profile it is
		// the first thing a new user sees under their new bank value. Grey, like L11's "No 180d history"; red is
		// kept for the two sentences that name something to act on.
		final String pending = PriceService.problemHistoryPending(MovementWindow.D180);
		publish(rows(1), listedWithProblem(pending, MovementWindow.D180, null));
		assertEquals(pending, panel.problemLabel().getText());
		assertEquals("a fetch in flight is not a fault", ColorScheme.LIGHT_GRAY_COLOR, panel.problemLabel().getForeground());
		assertTrue(panel.describe(), panel.describe().contains("\"problemRed\":false"));

		final String noHistory = PriceService.problemNoHistory(MovementWindow.D180);
		publish(rows(1), listedWithProblem(noHistory, MovementWindow.D180, null));
		assertTrue(panel.problemShowing());
		assertEquals(noHistory, panel.problemLabel().getText());
		assertEquals("L11: a fact about the wiki page, not a failure", ColorScheme.LIGHT_GRAY_COLOR, panel.problemLabel().getForeground());
		assertTrue(panel.describe(), panel.describe().contains("\"problemRed\":false"));

		publish(rows(1), listed(1, 1));
		assertFalse("removed when clear", panel.problemShowing());
		assertEquals("", panel.problemLabel().getText());
	}

	/**
	 * S1: the KIND decides the colour, not the wording. The service chooses it where it writes the sentence
	 * ({@code PriceService.ProblemKind}), and the panel asks {@code severe()} - so a sentence reworded, or one
	 * that happens to read like another, cannot turn a grey line red or hide a real fault in grey. The panel used
	 * to rebuild two sentences and compare strings, which made every wording a load-bearing constant.
	 */
	@Test
	public void theProblemsKindDecidesItsColourWhateverTheSentenceReads() throws Exception
	{
		build();
		// A severe kind is red even under the gentlest wording...
		publish(rows(1), listedWithProblem("No 180d history", PriceService.ProblemKind.HISTORY_DOWN,
			MovementWindow.D180, null));
		assertEquals("No 180d history", panel.problemLabel().getText());
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, panel.problemLabel().getForeground());
		assertTrue(panel.describe(), panel.describe().contains("\"problemRed\":true"));

		// ...and an absent-not-broken kind is grey under the loudest.
		publish(rows(1), listedWithProblem(UNAVAILABLE, PriceService.ProblemKind.PENDING, MovementWindow.D180, null));
		assertEquals(UNAVAILABLE, panel.problemLabel().getText());
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.problemLabel().getForeground());
		assertTrue(panel.describe(), panel.describe().contains("\"problemRed\":false"));

		// The four kinds a sentence can carry, as the service pairs them (K7, L11, K5).
		assertTrue(PriceService.ProblemKind.HISTORY_DOWN.severe());
		assertTrue(PriceService.ProblemKind.COOLDOWN.severe());
		assertFalse(PriceService.ProblemKind.PENDING.severe());
		assertFalse(PriceService.ProblemKind.NO_HISTORY.severe());
		assertFalse(PriceService.ProblemKind.NONE.severe());
	}

	@Test
	public void statusOnlyUpdateLeavesTheRowsAlone() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		final List<MovementRowPanel> first = panel.rowPanels();
		assertEquals(1, panel.rebuilds());

		// The same rows (a fresh, equal list) with a new status: the problem row appears, the list does not move.
		publish(new ArrayList<>(rows(3)), listedWithProblem(COOLDOWN, MovementWindow.D1, THEN_DAY));
		assertEquals("C30: no rebuild for a status-only publish", 1, panel.rebuilds());
		assertEquals(first, panel.rowPanels());
		assertTrue(panel.statusText(), panel.statusText().contains(COOLDOWN));
		assertTrue(panel.problemShowing());
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, panel.problemLabel().getForeground());

		// A different window with equal rows still rebuilds: the rows' tooltips name the window.
		publish(new ArrayList<>(rows(3)), status(true, true, 3, 3, MovementWindow.D7, null, STATUS_TEXT, PRICES_AT));
		assertEquals(2, panel.rebuilds());
		assertTrue(panel.rowPanels().get(0).tooltipHtml().contains("7d ago"));

		// Different rows rebuild.
		publish(rows(4), status(true, true, 4, 4, MovementWindow.D7, null, STATUS_TEXT, PRICES_AT));
		assertEquals(3, panel.rebuilds());
		assertEquals(4, panel.rowPanels().size());
	}

	/**
	 * The bridge's {@code status} field is still the whole sentence, the problem joined once (K7).
	 *
	 * <p>Note what the join is FOR. A real {@code Status} answers the problem sentence from {@code text()} when
	 * it has one, so the two-part state below is one only a mocked status can reach - the join is defensive, and
	 * {@link #aRealStatusFromTheServiceRendersTheWholeHeader} is where the shipped behaviour is pinned. Keep both:
	 * the rule "the problem appears once, whatever the service hands down" is the one the bridge depends on.
	 */
	@Test
	public void statusTextStillJoinsTheProblemOnce() throws Exception
	{
		build();
		assertEquals("", panel.statusText());
		publish(rows(1), listed(1, 1));
		assertEquals(STATUS_TEXT, panel.statusText());

		publish(rows(1), status(true, true, 1, 1, MovementWindow.D1, UNAVAILABLE, STATUS_TEXT, PRICES_AT));
		assertEquals(STATUS_TEXT + " - " + UNAVAILABLE, panel.statusText());
		assertTrue(panel.describe(), panel.describe().contains("\"status\":\"" + STATUS_TEXT + " - " + UNAVAILABLE + "\""));

		publish(rows(1), status(true, true, 1, 1, MovementWindow.D30, "No 30d history yet",
			"Guide prices - No 30d history yet", PRICES_AT));
		assertEquals("a problem already in the text is not doubled", "Guide prices - No 30d history yet", panel.statusText());

		publish(rows(1), status(true, true, 1, 1, MovementWindow.D1, "Only the problem", "", 0L));
		assertEquals("Only the problem", panel.statusText());
		assertEquals("Only the problem", panel.problemLabel().getText());
	}

	// ---- rows and paging

	/** N section 3 §4: every row on the card grey with a 2 px gutter - the zebra is gone. */
	@Test
	public void rowsAppearFromOnRowsOnFlatCardsWithPictures() throws Exception
	{
		build();
		final List<MovementRow> three = rows(3);
		publish(three, listed(3, 3));
		onEdt(() ->
		{
			final List<MovementRowPanel> panels = panel.rowPanels();
			assertEquals(3, panels.size());
			assertSame(three.get(0), panels.get(0).row());
			assertSame(three.get(2), panels.get(2).row());
			for (MovementRowPanel p : panels)
			{
				assertEquals("no zebra", ColorScheme.DARKER_GRAY_COLOR, p.getBackground());
			}
			assertEquals("a 2 px gutter between the cards", 2, ((DynamicGridLayout) panel.rowsColumn().getLayout()).getVgap());
			assertEquals(3, panel.shownRows());
			assertEquals(3, panel.totalRows());
			assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());
			assertFalse(panel.showMoreVisible());
			assertEquals("All items", panel.bandTarget().getText());
		});
		verify(itemManager).getImage(1, 1, false);
		verify(itemManager).getImage(2, 2, true);
		verify(itemManager).getImage(3, 3, true);
	}

	/**
	 * AL: the two figures on a row's second line stop competing - the gp figure takes the QUIET role and the
	 * percentage keeps the full one.
	 *
	 * <p>The user, on a full list: "can you brainstorm some ideas for making the change in gp (+3,432) and the
	 * percent change (+23.5%) ... be more isolated from each other?" Four variants were drawn at the real width
	 * and they chose the dimmed gp. So a moving row now paints two DIFFERENT colours on one line, where it
	 * painted one twice before, and the difference is the whole change - hence the {@code assertNotEquals}.
	 *
	 * <p><b>Asked of {@link Widgets} and never of a hex value.</b> The palette is one source - {@code Widgets.move}
	 * mixes QUIET from FIGURE, and FIGURE lifts the falling red (B045) - so a test carrying its own copies of the
	 * numbers would pin the mix as it stands today and fail the next time the user's eye moves it, which is the
	 * one kind of change this pair must survive. What is pinned is the RELATIONSHIP: which role each figure
	 * takes.
	 *
	 * <p>The flat row is asserted from the other end, and the Q4 row format did NOT change it: a row whose
	 * price has not moved still prints no gp figure at all, on either line, because "0.0%" beside it is already
	 * the whole of what there is to say and a row reading "0 ... 0 ... 0.0%" says one fact three times. The
	 * column survives the blank - {@code GP_COLUMN} is a fixed box that an empty label holds open exactly as a
	 * filled one does - and both lines blank together, so line 3's figure is never left standing alone under a
	 * gap. The rule underneath is unmoved too: QUIET and FIGURE agree at a signum of zero, because pushing a
	 * grey figure toward the background makes it harder to read and says nothing about a direction it does not
	 * have. That agreement is what AL promised about a row that has not moved, and it is asserted here even
	 * though no figure is currently painted with it.
	 */
	@Test
	public void theGpFigureIsDimmedAndThePercentageBesideItIsNot() throws Exception
	{
		build();
		publish(Arrays.asList(
			new MovementRow(1, "Riser", 1, false, 1_100L, 1_000L, 100L, 10.0d, 1_100L, null),
			new MovementRow(2, "Faller", 1, false, 900L, 1_000L, -100L, -10.0d, 900L, null),
			new MovementRow(3, "Flat", 1, false, 1_000L, 1_000L, 0L, 0.0d, 1_000L, null)),
			listed(3, 3));
		onEdt(() ->
		{
			final List<MovementRowPanel> panels = panel.rowPanels();
			for (int i = 0; i <= 1; i++)
			{
				final MovementRowPanel p = panels.get(i);
				final int signum = i == 0 ? 1 : -1;
				final String why = p.row().name() + ": ";
				final JLabel gp = gpLabelOf(p);
				assertNotNull(why + "it has a gp figure to dim", gp);
				assertEquals(why + "AL: the gp figure takes the QUIET role",
					Widgets.move(signum, Widgets.Kind.QUIET), gp.getForeground());
				assertEquals(why + "...and the percentage keeps the full FIGURE colour",
					Widgets.move(signum, Widgets.Kind.FIGURE), p.changeColor());
				assertNotEquals(why + "AL: which is the point - the two no longer read as one figure",
					gp.getForeground(), p.changeColor());
				assertEquals(why + "the row's own helper is what painted it", MovementRowPanel.quietChangeColor(p.row()),
					gp.getForeground());
			}

			final MovementRowPanel flat = panels.get(2);
			assertEquals("a flat row prints no gp figure", "", flat.gpText());
			assertNull("...so there is no label on its face to dim", gpLabelOf(flat));
			// Line 3's figure has no accessor, so it is asked of the card: nothing anywhere on a flat row
			// prints a bare zero, which is the half of the rule gpText() alone cannot answer.
			final List<JComponent> flatParts = new ArrayList<>();
			descendants(flat, flatParts);
			for (JComponent c : flatParts)
			{
				if (c instanceof JLabel)
				{
					assertNotEquals("neither line of a flat row prints a zero", "0", ((JLabel) c).getText());
				}
			}
			assertEquals("the percentage is the quiet grey", Widgets.move(0, Widgets.Kind.FIGURE),
				flat.changeColor());
			assertEquals("and QUIET takes it UNDIMMED: a grey dash pushed toward the background only reads worse",
				Widgets.move(0, Widgets.Kind.FIGURE), Widgets.move(0, Widgets.Kind.QUIET));
			assertEquals(Widgets.move(0, Widgets.Kind.QUIET), MovementRowPanel.quietChangeColor(flat.row()));
		});
	}

	@Test
	public void aPictureTheManagerCannotGiveIsNotAnError() throws Exception
	{
		when(itemManager.getImage(anyInt(), anyInt(), anyBoolean())).thenThrow(new IllegalStateException("no client"));
		build();
		publish(rows(2), listed(2, 2));
		assertEquals(2, panel.rowPanels().size());
		assertNull(panel.rowPanels().get(0).icon());
	}

	/**
	 * L7: the baseline DAY is stamped into every row's tooltip, so a NEW day has to rebuild the rows even when
	 * it moved no price at all - which is the ordinary case for the long windows on a quiet day.
	 */
	@Test
	public void aNewBaselineDayRebuildsTheRowsEvenWithIdenticalPrices() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		assertEquals(1, panel.rebuilds());
		assertEquals(THEN_DAY, panel.listThenDay());
		final String before = panel.rowPanels().get(0).tooltipHtml();
		assertTrue(before, before.contains("1d ago (07 Sep):"));

		final LocalDate newer = THEN_DAY.plusDays(1);
		publish(new ArrayList<>(rows(3)), status(true, true, 3, 3, MovementWindow.D1, null, STATUS_TEXT, PRICES_AT, newer));
		assertEquals(2, panel.rebuilds());
		assertEquals(newer, panel.listThenDay());
		final String after = panel.rowPanels().get(0).tooltipHtml();
		assertTrue(after, after.contains("1d ago (08 Sep):"));

		// The same day again with the same rows is still a status-only publish.
		publish(new ArrayList<>(rows(3)), status(true, true, 3, 3, MovementWindow.D1, null, STATUS_TEXT, PRICES_AT, newer));
		assertEquals(2, panel.rebuilds());
	}

	@Test
	public void aStatusWithNoBaselineDayIsNotAnError() throws Exception
	{
		build();
		publish(rows(3), status(true, true, 3, 3, MovementWindow.D180, null, "Guide prices - Bank as of 13:52", PRICES_AT, null));
		assertEquals(1, panel.rebuilds());
		assertNull(panel.listThenDay());
		assertTrue(panel.rowPanels().get(0).tooltipHtml().contains("180d ago (-):"));

		publish(new ArrayList<>(rows(3)), status(true, true, 3, 3, MovementWindow.D180, null,
			"Guide prices - Bank as of 13:52", PRICES_AT, null));
		assertEquals(1, panel.rebuilds());

		// ...and the day landing IS a rebuild.
		publish(new ArrayList<>(rows(3)), status(true, true, 3, 3, MovementWindow.D180, null, STATUS_TEXT, PRICES_AT, THEN_DAY));
		assertEquals(2, panel.rebuilds());
	}

	/**
	 * A new sort COLUMN rebuilds the rows on the next publish even when the list came back in the same order -
	 * the rows on screen belong to the column they were listed under, and the reader asked for another one, so
	 * they start again at the top of page one (B107).
	 *
	 * <p>The rows themselves no longer record it: since addendum O nothing on a row's face varies with the
	 * ordering (O1), so the panel decides this from the filter it built the page from, and the proof is that a
	 * rebuild HAPPENED - not that a row is carrying a value nobody draws.
	 */
	@Test
	public void aSortChangeRebuildsTheRowsOnTheNextPublishEvenWithTheSameOrder() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		assertEquals(1, panel.rebuilds());
		final List<MovementRowPanel> first = panel.rowPanels();

		onEdt(() -> panel.setSort(SortMode.GP_MOVE, true));
		assertEquals("the list waits for the publish", 1, panel.rebuilds());
		publish(new ArrayList<>(rows(3)), listed(3, 3));
		assertEquals("equal rows, new column: rebuilt", 2, panel.rebuilds());
		assertNotEquals("...with new row cards", first, panel.rowPanels());
		assertEquals(SortMode.GP_MOVE, panel.filter().sort());

		publish(new ArrayList<>(rows(3)), listed(3, 3));
		assertEquals("and the same column again is a status-only publish", 2, panel.rebuilds());

		// The DIRECTION alone moves no column, so an equal list restated under it is not rebuilt either.
		onEdt(() -> panel.setSort(SortMode.GP_MOVE, false));
		publish(new ArrayList<>(rows(3)), listed(3, 3));
		assertEquals(2, panel.rebuilds());
	}

	// ----------------------------------- addenda AG and AI: which rows have their description block open

	/**
	 * <b>THE ONE THAT MATTERS.</b> A row the reader clicked open is still open after the next publish has rebuilt
	 * the page - and it is a different row card that is standing open.
	 *
	 * <p>This is the assertion the naive design dies on, so read what the naive design was. A
	 * {@link MovementRowPanel} is not long-lived: {@code rebuildRows} calls {@code removeAll()} and {@code addPage}
	 * builds the list again from NEW instances on every publish whose rows differ - a refresh, a bank opening, the
	 * half-hourly price recheck. The obvious place for "is this row expanded?" is a boolean field on the row, and
	 * it passes every test you can write against one row: it opens, the block appears, it shuts again. Then the
	 * plugin ships, the reader clicks a row open to read where its price came from, prices are rechecked half an
	 * hour later, and the block they are reading folds itself up with nothing on screen to explain why. So the
	 * state lives on the PANEL ({@code expandedIds} behind one {@link MovementRowPanel.Expansion}), handed to every
	 * row {@code addPage} builds, and the proof of it is here: {@link #repriced} publishes the same items with new
	 * prices, the card at that position is a different OBJECT afterwards, and it is still open while both its
	 * neighbours are shut.
	 *
	 * <p><b>Re-pointed by addendum AI.</b> Until AI the observable was which of two TOOLTIPS a row was carrying;
	 * the description is not a hover any more, so it is the cell's own geometry instead - the detail block
	 * showing, and the card grown by exactly what that block asks for ({@link #assertOpen}). Same seam, same
	 * promise, and now the thing a reader actually sees.
	 */
	@Test
	public void aRowClickedOpenIsStillOpenAfterThePageIsRebuilt() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		final MovementRowPanel clicked = panel.rowPanels().get(1);
		assertClosed("every row starts shut", clicked);
		onEdt(() -> clickRow(clicked));
		assertOpen("the click opened it", clicked);

		// The half-hourly recheck: the same three items, a gp dearer. Nobody asked for it, and it rebuilds.
		publish(repriced(3), listed(3, 3));
		assertEquals("the page really was rebuilt", 2, panel.rebuilds());
		final List<MovementRowPanel> after = panel.rowPanels();
		assertNotSame("...from new row cards, which is the whole point", clicked, after.get(1));
		assertEquals("Item 2", after.get(1).row().name());
		assertOpen("AG: the reader's click outlived the rebuild", after.get(1));
		assertClosed("and it opened one row, not the list", after.get(0));
		assertClosed("and it opened one row, not the list", after.get(2));

		// Clicking again folds it away, and THAT survives the next rebuild the same way - the state is the
		// reader's answer either way round, not a flag that only ever gets set.
		onEdt(() -> clickRow(after.get(1)));
		assertClosed("clicked again, folded away", after.get(1));
		publish(rows(3), listed(3, 3));
		assertEquals(3, panel.rebuilds());
		assertClosed("and it stays folded across the rebuild", panel.rowPanels().get(1));
	}

	/**
	 * AI: the GEOMETRY survives the rebuild too, and not just the flag.
	 *
	 * <p>The test above reads {@link #assertOpen}, which asks both questions at once; this one asks the second
	 * alone and says why it is a question of its own. A row seeds itself from the panel's
	 * {@link MovementRowPanel.Expansion} when it is BUILT, and a build that read the state without APPLYING it
	 * would leave the new card reporting "open" while it was drawn as the shut strip - which passes every
	 * assertion about the seam and still shows the reader a list that folded itself up. So the height of the
	 * card the rebuild produced is pinned against the height of the card the click produced, by the px.
	 */
	@Test
	public void anOpenRowKeepsItsTallerHeightAcrossARebuild() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		final MovementRowPanel clicked = panel.rowPanels().get(1);
		final int shut = clicked.getPreferredSize().height;
		assertEquals("a shut row is the 62 px card (addendum N section 2, at the Q4 height)",
			MovementRowPanel.ROW_HEIGHT, shut);
		onEdt(() -> clickRow(clicked));
		final int open = clicked.getPreferredSize().height;
		assertTrue("the cell grew to hold the description: " + open, open > shut);

		publish(repriced(3), listed(3, 3));
		final MovementRowPanel rebuilt = panel.rowPanels().get(1);
		assertNotSame("a new card", clicked, rebuilt);
		assertOpen("AI: the block is still showing", rebuilt);
		assertEquals("AI: and the card is still the taller one, to the px", open,
			rebuilt.getPreferredSize().height);
		assertEquals("...while its neighbours are the plain 62 px strip", MovementRowPanel.ROW_HEIGHT,
			panel.rowPanels().get(0).getPreferredSize().height);
	}

	/**
	 * AG: the state is keyed by ITEM ID and not by list position, so an open row follows its item through a
	 * re-sort rather than staying at the position the item used to sit at.
	 *
	 * <p>Keying by position would look right for exactly as long as the list never moved, and this panel's list
	 * moves on four different gestures (another column, another direction, another price band, another window).
	 * A reader who opened "Twisted bow" and then sorted by stack value would find some unrelated row open and
	 * the bow folded.
	 */
	@Test
	public void anOpenRowFollowsItsItemThroughAReSort() throws Exception
	{
		build();
		publish(rows(8), listed(8, 8));
		final MovementRowPanel atFive = panel.rowPanels().get(5);
		final int item = atFive.row().id();
		assertEquals("the fixture puts item 6 at position 5", 6, item);
		onEdt(() -> clickRow(atFive));
		assertOpen("opened at position 5", atFive);

		// The reader picks another column, and the service answers with the same eight items the other way up.
		onEdt(() -> panel.setSort(SortMode.STACK_VALUE, true));
		final List<MovementRow> reversed = new ArrayList<>(rows(8));
		Collections.reverse(reversed);
		publish(reversed, listed(8, 8));

		final List<MovementRowPanel> after = panel.rowPanels();
		assertEquals("the list really did turn over", item, after.get(2).row().id());
		assertOpen("AG: the open cell followed the ITEM", after.get(2));
		assertClosed("and not the position it was clicked at", after.get(5));
	}

	/**
	 * AG: two rows open independently, and closing one says nothing about the other - the state is a SET of item
	 * ids, not one "the open row" slot.
	 */
	@Test
	public void twoRowsOpenIndependentlyAndClosingOneLeavesTheOtherOpen() throws Exception
	{
		build();
		publish(rows(4), listed(4, 4));
		final List<MovementRowPanel> first = panel.rowPanels();
		onEdt(() ->
		{
			clickRow(first.get(0));
			clickRow(first.get(3));
		});
		assertOpen("the first row the reader opened", first.get(0));
		assertClosed("untouched", first.get(1));
		assertClosed("untouched", first.get(2));
		assertOpen("the second, and opening it did not close the first", first.get(3));

		onEdt(() -> clickRow(first.get(0)));
		assertClosed("closed again", first.get(0));
		assertOpen("closing one row leaves the other open", first.get(3));

		// And both readings - one open, one closed - come back together after a rebuild.
		publish(repriced(4), listed(4, 4));
		final List<MovementRowPanel> after = panel.rowPanels();
		assertClosed("the closed one stays closed", after.get(0));
		assertClosed("and the two that were never clicked stay shut", after.get(1));
		assertClosed("and the two that were never clicked stay shut", after.get(2));
		assertOpen("the open one stays open", after.get(3));
	}

	/**
	 * AG: nothing is open until the reader opens it - on the first page, and on a page built later by
	 * "Show n more", which is built from the same {@link MovementRowPanel.Expansion} and must agree with it.
	 */
	@Test
	public void aFreshPanelStartsWithEveryRowShut() throws Exception
	{
		build();
		publish(rows(5), listed(5, 5));
		for (MovementRowPanel row : panel.rowPanels())
		{
			assertClosed(row.row().name() + " starts shut", row);
		}

		// The second page is built minutes later by a gesture of its own; it starts shut too.
		publish(rows(300), listed(300, 300));
		assertEquals(250, panel.rowPanels().size());
		onEdt(() -> panel.showMore());
		assertEquals(300, panel.rowPanels().size());
		assertClosed("a row built by the second page", panel.rowPanels().get(275));

		// ...and a row opened on page two behaves like any other: it survives the rebuild, in its own place.
		final MovementRowPanel onPageTwo = panel.rowPanels().get(275);
		onEdt(() -> clickRow(onPageTwo));
		assertOpen("opened on page two", onPageTwo);
		publish(repriced(300), listed(300, 300));
		assertEquals("the rebuild keeps the pages the reader opened", 300, panel.rowPanels().size());
		assertOpen("and the click with them", panel.rowPanels().get(275));
		assertClosed("its neighbour is untouched", panel.rowPanels().get(274));
	}

	/**
	 * AK: the open cell is a LABEL COLUMN, and its change line names the WINDOW it is measuring.
	 *
	 * <p>The user asked for the block to be simplified - "This looks really hard to read, can you please pitch
	 * me some ideas ... simplify, layman talk" - chose the labelled option, and then corrected it: "i find the
	 * change confusing, make it show how many days (1d, 7d, 30d, 90d, 180d) so itll be Change 1d or Change 7d
	 * for example". The correction is the half a later tidy-up would lose, because "Change" alone looks
	 * complete: the lit chip is at the top of the sidebar, a screen away from the figure, so a block that does
	 * not name its own window leaves the reader to remember which one they picked.
	 *
	 * <p>{@link #assertOpen} already pins the block against {@code MovementRowPanel.detail(...)}, which is what
	 * keeps the two from drifting. What is added here is the part that call cannot show, because it is handed
	 * the same window the panel would hand it: that the window reaching the block is the one the PAGE was built
	 * for, so a reader on 7d reads "Change 7d" and never yesterday's label.
	 */
	@Test
	public void theOpenCellIsALabelColumnWhoseChangeLineNamesTheWindow() throws Exception
	{
		build();
		publish(rows(3), status(true, true, 3, 3, MovementWindow.D7, null, STATUS_TEXT, PRICES_AT));
		final MovementRowPanel row = panel.rowPanels().get(1);
		onEdt(() -> clickRow(row));
		assertOpen("AK: the labelled block", row);

		final String text = detailOf(row).getText();
		assertTrue(text, text.contains(MovementRowPanel.L_NOW));
		assertTrue(text, text.contains(MovementRowPanel.L_WAS));
		assertTrue(text, text.contains(MovementRowPanel.L_HAVE));
		assertTrue("AK: the change names the window the page is drawn for",
			text.contains(MovementRowPanel.L_CHANGE + MovementWindow.D7.label()));
		assertFalse("and not the one it is not", text.contains(MovementRowPanel.L_CHANGE + MovementWindow.D1.label()));

		// The reader moves to another window: the page is rebuilt, and the block follows it.
		publish(repriced(3), status(true, true, 3, 3, MovementWindow.D30, null, STATUS_TEXT, PRICES_AT));
		final MovementRowPanel after = panel.rowPanels().get(1);
		assertOpen("AK: still open on the new window", after);
		assertTrue("AK: and re-labelled with it", detailOf(after).getText()
			.contains(MovementRowPanel.L_CHANGE + MovementWindow.D30.label()));
	}

	// ------------------------------- addendum AJ: the hover switch, back, with its reach narrowed by AI

	/**
	 * AJ: the switch's gear item - where it is, and what it is made of.
	 *
	 * <p>The user drew the place in AH ("exactly where i drew the orange box"), and asked for it again in AJ
	 * after AI had deleted the switch along with the row hovers it used to govern: "where is the show hover
	 * text box and wording and default 'off' setting? it should be the row above OK". So it is the LAST item in
	 * the menu, directly after the preset row and directly above addendum AB's OK row, in that group rather
	 * than one of its own. ("Reset to default" stood between the row and the switch until AH2 moved it into
	 * that OK row, and the switch did not move with it.)
	 *
	 * <p><b>And it is a plain {@link JMenuItem} carrying a drawn icon, never a {@link JCheckBoxMenuItem}.</b>
	 * That is the one thing in this addendum a later tidy-up would undo without noticing: RuneLite's look and
	 * feel paints an UNSELECTED check item as blank, so a switch that ships OFF would look exactly like a
	 * command until someone turned it on, and nobody turns on a control they cannot see is a control. The type
	 * is therefore pinned here, beside the two other halves of the same promise - the box is on the LEFT and the
	 * label to the RIGHT of it, both hard against the menu's left edge, which is what the user asked for in the
	 * same breath.
	 */
	@Test
	public void theHoverSwitchIsTheLastGearItemAndDrawsItsOwnBox() throws Exception
	{
		buildWithHovers();
		final JPopupMenu menu = panel.heroMenu();
		final JMenuItem hoverItem = item(menu, BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT);
		assertEquals("the words addendum AH asks for and AJ puts back, pinned", "Show hover text",
			BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT);
		assertEquals(BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT, hoverItem.getText());

		assertSame("the last item in the menu", hoverItem, menu.getComponent(menu.getComponentCount() - 2));
		assertSame("the panel's own accessor names the same item", panel.showHoverTextItem(), hoverItem);
		assertSame("directly after the preset row (AH2 took the reset item out from between them)",
			panel.presetRow(), menu.getComponent(menu.getComponentCount() - 3));
		assertSame("AJ: and directly above the OK row (AB2) - \"it should be the row above OK\"", panel.okRow(),
			menu.getComponent(menu.getComponentCount() - 1));
		assertTrue("that row is where the way back to the default bands lives now",
			SwingUtilities.isDescendingFrom(panel.resetPresetsButton(), panel.okRow()));
		assertFalse("no rule between: it is in the last group, not a group of its own",
			menu.getComponent(menu.getComponentCount() - 3) instanceof JSeparator);

		assertFalse("AH: a check item would be BLANK while this switch is off, which is most of the time",
			hoverItem instanceof JCheckBoxMenuItem);
		assertTrue("...and the check items beside it really are the other kind",
			panel.countInventoryItem() instanceof JCheckBoxMenuItem);
		assertNotNull("it carries a drawn box of its own", hoverItem.getIcon());
		assertNull("which the check items do not", panel.countInventoryItem().getIcon());

		assertEquals("the box is on the left", SwingConstants.LEFT, hoverItem.getHorizontalAlignment());
		assertEquals("and the label to the right of it", SwingConstants.RIGHT,
			hoverItem.getHorizontalTextPosition());
		assertEquals("in the menu's own face", Widgets.sans(12), hoverItem.getFont());
		// Read under the switch, because since AH3 there is nothing to read without it - the item's own line
		// goes quiet with every other, which is the whole of the user's "no hover text at all unless it is on".
		assertEquals("and with the switch on it explains itself like everything else",
			BankPriceMovementPanel.SHOW_HOVER_TEXT_TIP, hoverItem.getToolTipText());
		// AJ narrowed the SENTENCE with the reach. AH's read "the bank value, the item rows and the controls";
		// addendum AI moved a row's description out of its hover and into the cell, so a row is silent at every
		// setting and the switch has nothing left to say about one. A description that still promised the rows
		// would be the item telling the reader something untrue.
		assertEquals("the description addendum AJ asks for, pinned",
			"Show hover text anywhere in the sidebar: the bank value and the controls",
			BankPriceMovementPanel.SHOW_HOVER_TEXT_TIP);
	}

	/**
	 * AJ: "empty when off, ticked when on" - the user's own words for the box in AH - the DEFAULT they asked
	 * for again in AJ ("and default 'off' setting"), and the click that flips it.
	 *
	 * <p>The two glyphs are compared as PIXELS, the way every other drawn icon in this file is ({@code sameIcon}
	 * over {@code Widgets.triangle}): they have no {@code equals} of their own, and the promise is about what a
	 * reader sees rather than about which object the item is holding. Both states draw ink - an empty box is
	 * still a box - and the ticked one draws more of it.
	 */
	@Test
	public void theHoverSwitchesBoxIsEmptyWhenOffAndTickedWhenOnAndTheClickWritesThePref() throws Exception
	{
		build();
		assertFalse("AJ: the quieter sidebar is what ships, as the user asked twice",
			panel.options().showHoverText());
		publish(rows(3), listedWith(summary()));
		final JMenuItem hoverItem = item(panel.heroMenu(), BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT);
		final Icon empty = Widgets.checkBox(false);
		final Icon ticked = Widgets.checkBox(true);
		assertFalse("the two states are not the same picture", sameIcon(empty, ticked));
		assertTrue("an empty box is still a box: the outline is drawn", inked(empty) > 0);
		assertTrue("and the tick is ink on top of it", inked(ticked) > inked(empty));
		assertTrue("off out of the box", sameIcon(empty, hoverItem.getIcon()));

		// The click: the switch flips, the box fills, and the pref is written so the settings page follows.
		onEdt(() -> hoverItem.doClick(0));
		assertTrue(panel.options().showHoverText());
		assertTrue("the box follows the click", sameIcon(ticked, hoverItem.getIcon()));
		assertEquals("the pref is written so the config panel follows", Arrays.asList(HOVERS_ON),
			prefs.optionSaves);
		assertTrue("the filter and the hero switches are untouched", prefs.saves.isEmpty());
		assertTrue(prefs.heroSaves.isEmpty());

		// ...and again, back off.
		onEdt(() -> hoverItem.doClick(0));
		assertFalse(panel.options().showHoverText());
		assertTrue(sameIcon(empty, hoverItem.getIcon()));
		assertEquals(2, prefs.optionSaves.size());
		assertEquals(ViewOptions.DEFAULT, prefs.optionSaves.get(1));

		// The settings page's own change comes back through applyOptions: it redraws the box and writes nothing.
		onEdt(() -> panel.applyOptions(HOVERS_ON));
		assertTrue("the box follows the config road too", sameIcon(ticked, hoverItem.getIcon()));
		assertEquals(2, prefs.optionSaves.size());
		onEdt(() -> panel.applyOptions(ViewOptions.DEFAULT));
		assertTrue(sameIcon(empty, hoverItem.getIcon()));
		assertEquals(2, prefs.optionSaves.size());
	}

	/**
	 * AJ: the hero card's hover - addendum AF's exact gp figure - is SILENT out of the box, and turning the
	 * switch on brings it back exactly as AF left it.
	 *
	 * <p>This is addendum AH's data-hover test with one half taken away. AH governed two hovers, the card's and
	 * an item row's; AI moved a row's description out of the hover and into the cell, so the card's is the only
	 * DATA hover left for the switch to hold, and the rows have a test of their own saying they are silent
	 * whichever way it is set ({@link #aRowIsSilentUnderEitherSettingAndStillOpensOnAClick}).
	 *
	 * <p><b>Null and not the empty string.</b> Swing's {@code ToolTipManager} registers a component the moment
	 * it is given any text and opens an empty yellow box for {@code ""} - which is the very reading ("a box
	 * follows my pointer around") the switch exists to remove. Every assertion here says {@code assertNull}
	 * rather than {@code assertEquals("", ...)} for that reason.
	 */
	@Test
	public void theCardsHoverIsSilentUntilTheSwitchIsTurnedOn() throws Exception
	{
		build();
		assertFalse("AJ: the quieter sidebar is what ships", panel.options().showHoverText());
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			for (JComponent c : heroTipTargets())
			{
				assertNull(c.getClass().getSimpleName() + " carries no hover while the switch is off",
					c.getToolTipText());
			}
		});

		// The settings page's own road in: the same panel, the next publish, the hover back.
		onEdt(() -> panel.applyOptions(HOVERS_ON));
		publish(repriced(3), listedWith(summary()));
		onEdt(() ->
		{
			for (JComponent c : heroTipTargets())
			{
				assertEquals(c.getClass().getSimpleName() + " is back on the card's hover", VALUE_TIP,
					c.getToolTipText());
			}
		});
	}

	/**
	 * AH3, the scope decision AJ keeps: the switch governs every hover that explains a CONTROL, and not only
	 * the card's figure.
	 *
	 * <p>AH as first built spared them - the sort button, the Refresh link, the "Item prices update every 24hrs"
	 * line, the gear, the window chips, the band button and the items in the gear menu kept their words in both
	 * states, on the reasoning that a control which will not say what it does is a worse sidebar rather than a
	 * quieter one. The user, on that build: "there are still some things that show hover text even when its
	 * 'off' please fix this and make sure there is no hover text at all unless it is on." So the reasoning was
	 * wrong about what they asked for, and this test is the old one read backwards - silent with the switch off,
	 * word for word with it on, and silent again the moment it goes back.
	 *
	 * <p>The literal texts of the ON half are the old test's, unchanged: narrowing the switch's reach to leave
	 * the item rows out (AI, AJ) is no licence to change what a CONTROL says.
	 */
	@Test
	public void everyControlGoesQuietWithTheSwitchAndSaysItsPieceAgainWhenItComesBack() throws Exception
	{
		build();
		publish(rows(3), listedWith(summary()));
		assertControlTooltipsSilent("the switch OFF");

		onEdt(() -> panel.applyOptions(HOVERS_ON));
		publish(repriced(3), listedWith(summary()));
		assertControlTooltipsIntact("the switch ON");

		// ...and off again on the flip ALONE, with no publish behind it: applyHoverSwitch re-applies every hover
		// the panel has registered, and a control that only went quiet on the next publish would be a control
		// that stayed loud for up to half an hour (the price re-check interval) after the box was unticked.
		onEdt(() -> panel.applyOptions(ViewOptions.DEFAULT));
		assertControlTooltipsSilent("the switch OFF again, on the flip alone");

		// ...and back on the same way, so the flip is lossless in both directions - the map AH3 keeps holds what
		// each component says while the switch is on, and turning it off must not empty it.
		onEdt(() -> panel.applyOptions(HOVERS_ON));
		assertControlTooltipsIntact("the switch ON again, on the flip alone");
	}

	/**
	 * AJ: <b>not one component anywhere in this sidebar carries a tooltip while the switch is off</b> - walked,
	 * not listed - and not one component inside a ROW carries one at either setting.
	 *
	 * <p>Two guards over one traversal, because they answer two different failures.
	 *
	 * <p>The FIRST is addendum AH3's, and it is the guard that would have caught the bug the user found. The
	 * first cut of AH silenced the two hovers it knew about and missed four labels, because
	 * {@link Widgets#setFitted} hangs the whole text on a label it had to CUT and does it behind the panel's
	 * back. So the sidebar still spoke - over the bank total, the provenance footnote, the band button and the
	 * problem line - and it spoke exactly when a reader goes looking, which is when the label is too narrow to
	 * read. A test naming components one by one could not have caught that, because nobody writing it would
	 * have thought to name them; this one walks the panel's whole tree, and both popup menus with it, and fails
	 * on whatever it finds.
	 *
	 * <p>The SECOND is addendum AI's, and is the part AJ has to be careful about: a ROW is silent whether the
	 * switch is on or off, because since AI a row's description is the block the cell opens rather than a
	 * hover, and {@link Widgets#setFittedName} would otherwise leave a cut name and a cut price talking behind
	 * the row's back ({@code MovementRowPanel.clearHovers} is what answers that). A reader of this file will
	 * assume the switch covers the rows - it did, for one afternoon - so the ON half asserts outright that it
	 * does not.
	 *
	 * <p>The failure message names the offending component's class, the words on its face and the text it was
	 * showing, so the next person to add a {@code setToolTipText} outside {@code setHover} is told where.
	 *
	 * <p>Every state that puts something extra in the tree is walked: the fold open (the chips and the Min /
	 * Max boxes), a problem row, an OPEN row, and the empty card with its "Clear price range" button, which
	 * hangs off a card the reader only sees when a band has hidden everything.
	 */
	@Test
	public void notOneComponentHoversWithTheSwitchOffAndNoRowHoversWithItOn() throws Exception
	{
		build();
		assertFalse("AJ: the quieter sidebar is what ships", panel.options().showHoverText());
		onEdt(() ->
		{
			panel.toggleFold();
			panel.applyFilter(RowFilter.DEFAULT.withGpMin(123_456_789L).withGpMax(2_000_000_000L));
		});
		publish(rows(3), listedWithProblemAndPortfolio(UNAVAILABLE));
		onEdt(() ->
		{
			// The walk reaches the corners - asserted, so this can never pass by finding nothing.
			final Map<JComponent, String> targets = everyHoverTarget();
			assertTrue("the hero card", targets.containsKey(panel.hero()));
			assertTrue("the bank total", targets.containsKey(panel.totalLabel()));
			assertTrue("the provenance footnote", targets.containsKey(panel.footnoteLabel()));
			assertTrue("the update line", targets.containsKey(panel.updateLabel()));
			assertTrue("the Refresh link", targets.containsKey(panel.refreshLabel()));
			assertTrue("the gear", targets.containsKey(panel.gearLabel()));
			assertTrue("a window chip", targets.containsKey(panel.windowChip(MovementWindow.D1)));
			assertTrue("the sort button", targets.containsKey(panel.sortButton()));
			assertTrue("the band button", targets.containsKey(panel.bandTarget()));
			assertTrue("the fold's chips", targets.containsKey(panel.presetCell(1)));
			assertTrue("the Min box", targets.containsKey(panel.minField()));
			assertTrue("the Max box", targets.containsKey(panel.maxField()));
			assertTrue("the problem line", targets.containsKey(panel.problemLabel()));
			assertTrue("a gear item", targets.containsKey(panel.countInventoryItem()));
			assertTrue("the switch's own item", targets.containsKey(panel.showHoverTextItem()));
			assertTrue("a preset box", targets.containsKey(panel.presetField(0)));
			assertTrue("the preset row", targets.containsKey(panel.presetRow()));
			assertTrue("the reset button", targets.containsKey(panel.resetPresetsButton()));
			assertTrue("the OK button", targets.containsKey(panel.okButton()));
			final MovementRowPanel first = panel.rowPanels().get(0);
			assertTrue("a row", targets.containsKey(first));
			final List<JComponent> rowParts = new ArrayList<>();
			descendants(first, rowParts);
			assertFalse("a row has children", rowParts.isEmpty());
			for (JComponent c : rowParts)
			{
				assertTrue("inside a row: " + name(c), targets.containsKey(c));
			}

			assertNothingHovers("the switch OFF");

			// An OPEN row is the description's own state, and it must be as silent as a shut one.
			clickRow(first);
			assertOpen("the click opened it", first);
			assertNothingHovers("the switch OFF, with a row clicked open");
		});

		// ON, on the flip alone: the controls and the card speak, and the rows do not - which is AJ's whole
		// narrowing, asserted where a reader will come looking for it.
		onEdt(() ->
		{
			panel.applyOptions(HOVERS_ON);
			assertNoRowHovers("the switch ON");
			for (JComponent c : heroTipTargets())
			{
				assertEquals(name(c), VALUE_TIP, c.getToolTipText());
			}
		});
		assertControlTooltipsIntact("the switch ON");

		// The empty card hangs a button of its own in the tree, on a card nobody sees until a band hides
		// everything - the kind of corner a hand-written list of components forgets.
		onEdt(() -> panel.applyOptions(ViewOptions.DEFAULT));
		publish(Collections.emptyList(), listed(10, 0));
		onEdt(() ->
		{
			assertTrue("the empty card is showing its button", panel.clearBandShowing());
			assertTrue(everyHoverTarget().containsKey(panel.clearBandButton()));
			assertNothingHovers("the switch OFF, on the empty card");
			panel.applyOptions(HOVERS_ON);
			assertNotNull("and it explains itself again with the switch on",
				panel.clearBandButton().getToolTipText());
		});
	}

	/**
	 * AJ, and <b>the fact a future reader will get wrong</b>: the hover switch does not reach the item rows.
	 *
	 * <p>A row carries no tooltip with the switch ON and none with it OFF - on the card and on every child of
	 * it - and a click opens the cell in both states. That is not an oversight and it is not a bug waiting to
	 * be fixed: addendum AI moved a row's description out of the hover and into the block the cell opens, so
	 * there is no row hover for a switch to govern, and AJ restored the switch for the hovers AI left behind -
	 * the card's figure and the controls - rather than for these. Anyone who "fixes" this by wiring the rows
	 * back into {@code setHover} re-creates the seven-line tooltip the user asked to be rid of.
	 *
	 * <p>The click half matters as much as the silence. Under AH a click on a row swapped which of two tooltips
	 * it carried, so a row was inert while the hovers were off; since AI the click opens a block a reader can
	 * see, and it must work whatever the switch says.
	 */
	@Test
	public void aRowIsSilentUnderEitherSettingAndStillOpensOnAClick() throws Exception
	{
		buildWithHovers();
		publish(rows(4), listed(4, 4));
		assertTrue("the panel really is built with the switch on", panel.options().showHoverText());
		onEdt(() -> assertNoRowHovers("the switch ON: a row says nothing on hover since AI"));

		final MovementRowPanel openedWithHoversOn = panel.rowPanels().get(2);
		onEdt(() -> clickRow(openedWithHoversOn));
		assertOpen("the click opens the cell with the switch on", openedWithHoversOn);
		onEdt(() -> assertNoRowHovers("the switch ON, with a row open"));

		// Off, on the flip alone. The rows are deliberately NOT rebuilt for this switch - and since addendum AO
		// deleted holdingOnRows, applyOptions rebuilds them for no switch at all - so what is asserted here is
		// that they had nothing to lose by not being.
		onEdt(() -> panel.applyOptions(ViewOptions.DEFAULT));
		onEdt(() -> assertNoRowHovers("the switch OFF"));
		assertOpen("and the reader's open row is untouched by the flip", panel.rowPanels().get(2));

		final MovementRowPanel openedWithHoversOff = panel.rowPanels().get(0);
		onEdt(() -> clickRow(openedWithHoversOff));
		assertOpen("the click opens the cell with the switch off too", openedWithHoversOff);
		onEdt(() -> assertNoRowHovers("the switch OFF, with two rows open"));
	}

	/**
	 * AH3, kept by AJ: the four labels {@link Widgets#setFitted} speaks for follow the switch like everything
	 * else. They are named outright because they are the ones AH3 had to adopt - the real leak the user found -
	 * and the ones a later tidy-up would drop again.
	 *
	 * <p>The problem line is given a sentence too long for its 213 px row ON PURPOSE, and that it really was
	 * cut is asserted rather than assumed. {@code Widgets.setFitted} hangs the full text on a label only when
	 * it had to CUT it, so this file's usual problem sentence - which fits whole - leaves no tooltip at all and
	 * would prove nothing either way. The problem line is also the only one of the four whose hover is nothing
	 * BUT the fitted text, the total and the footnote carrying the card's own hover (AF) and the band button
	 * its band sentence, so it is the one that proves the adoption rather than a text written over it
	 * afterwards.
	 */
	@Test
	public void theFittedLabelsHoverWithTheSwitchOnAndAreSilentWithItOff() throws Exception
	{
		// Far past the 213 px the problem row has (N section 3 §2, T6), so the fitting helper must cut it.
		final String longProblem = "the last wiki history fetch failed - showing stored baselines from before"
			+ " the most recent Jagex guide-price publication";
		buildWithHovers();
		onEdt(() ->
		{
			panel.toggleFold();
			panel.applyFilter(RowFilter.DEFAULT.withGpMin(123_456_789L).withGpMax(2_000_000_000L));
		});
		publish(rows(3), listedWithProblemAndPortfolio(longProblem));
		onEdt(() ->
		{
			assertEquals("the bank total (AF)", VALUE_TIP, panel.totalLabel().getToolTipText());
			assertEquals("the provenance footnote", VALUE_TIP, panel.footnoteLabel().getToolTipText());
			assertNotNull("the band button", panel.bandTarget().getToolTipText());
			assertTrue("the fixture really is cut, so there is something here to leak",
				panel.problemLabel().getText().endsWith(Widgets.ELLIPSIS));
			assertFitted(panel.problemLabel(), longProblem);

			// ...and off on the flip alone, which is where the leak was: a label the panel never registered
			// would keep the fitting helper's own hover and go on talking with the box unticked.
			panel.applyOptions(ViewOptions.DEFAULT);
			assertNull("the bank total", panel.totalLabel().getToolTipText());
			assertNull("the provenance footnote", panel.footnoteLabel().getToolTipText());
			assertNull("the band button", panel.bandTarget().getToolTipText());
			assertNull("the problem line, still drawn cut", panel.problemLabel().getToolTipText());
			assertTrue("...and still drawn cut, so the silence is not the label having gone whole",
				panel.problemLabel().getText().endsWith(Widgets.ELLIPSIS));
		});
	}

	@Test
	public void listenerCalledOffTheEdtIsRePosted() throws Exception
	{
		build();
		assertFalse(SwingUtilities.isEventDispatchThread());
		listener.onRows(rows(2), listed(2, 2));
		onEdt(() ->
		{
		});
		assertEquals(2, panel.rowPanels().size());
	}

	/** C32 with N section 3 §5's wording: "Show 350 more", never a count line, never "Show all". */
	@Test
	public void pagingBuildsTwoHundredAndFiftyRowsAtATimeWithShowNMore() throws Exception
	{
		build();
		publish(rows(600), listed(600, 600));
		onEdt(() ->
		{
			assertEquals(250, panel.shownRows());
			assertEquals(600, panel.totalRows());
			assertEquals(250, panel.rowPanels().size());
			assertTrue(panel.showMoreVisible());
			assertEquals("Show 350 more", panel.showMoreLabel().getText());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.showMoreLabel().getForeground());
			assertEquals(new Dimension(W, BankPriceMovementPanel.SHOW_MORE_HEIGHT), panel.showMoreLabel().getParent().getPreferredSize());
			assertTrue(panel.describe(), panel.describe().contains("\"showMoreText\":\"Show 350 more\""));
			hover(panel.showMoreLabel(), true);
			assertEquals(Color.WHITE, panel.showMoreLabel().getForeground());
			hover(panel.showMoreLabel(), false);
			assertEquals("no count line: the band button states the band, not a count", "All items", panel.bandTarget().getText());

			press(panel.showMoreLabel());
			assertEquals(500, panel.shownRows());
			assertEquals("Show 100 more", panel.showMoreLabel().getText());
			assertTrue(panel.showMoreVisible());

			panel.showMore();
			assertEquals(600, panel.shownRows());
			assertFalse(panel.showMoreVisible());
			assertTrue(panel.describe(), panel.describe().contains("\"showMoreText\":\"\""));

			panel.showMore();
			assertEquals("nothing more to add is not an error", 600, panel.shownRows());
			assertEquals(600, panel.rowPanels().size());
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, panel.rowPanels().get(251).getBackground());
			for (String text : texts(panel.listView()))
			{
				assertFalse(text, text.contains("Show all"));
				assertFalse(text, text.startsWith("Showing "));
			}
		});
		verify(itemManager, times(600)).getImage(anyInt(), anyInt(), anyBoolean());
	}

	/**
	 * B107: the bank publishes on every deposit, withdrawal and rearrange, and every publish whose rows differ
	 * rebuilds the list - so a rebuild that only restates the SAME list has to leave the reader where they were.
	 * The pages they opened with "Show n more" and the place they scrolled to are their work; one item withdrawn
	 * from the bank they are standing at is not a reason to undo it.
	 */
	@Test
	public void aBankChangeKeepsThePagesTheReaderOpenedAndWhereTheyWere() throws Exception
	{
		build();
		publish(rows(600), listed(600, 600));
		final AtomicInteger place = new AtomicInteger();
		onEdt(() ->
		{
			panel.showMore();
			assertEquals(500, panel.shownRows());
			layout(SIDEBAR_WIDTH, 400);
			final JScrollBar bar = panel.scrollPane().getVerticalScrollBar();
			assertTrue("the fixture has to be a real, scrollable list: " + bar.getMaximum(), bar.getMaximum() > 1_000);
			bar.setValue(bar.getMaximum() / 2);
			assertTrue("...and to be scrolled into", bar.getValue() > 0);
			place.set(bar.getValue());
		});

		// One item withdrawn: a different row list, the same window, ordering, direction and band. The layout in
		// the same block is the client's own validate, which the posted restore is queued behind.
		onEdt(() ->
		{
			listener.onRows(rows(599), listed(599, 599));
			layout(SIDEBAR_WIDTH, 400);
		});
		onEdt(() ->
		{
		});
		onEdt(() ->
		{
			assertEquals("the second page the reader opened is still open", 500, panel.shownRows());
			assertEquals("Show 99 more", panel.showMoreLabel().getText());
			assertEquals("and they are still looking at the same rows",
				place.get(), panel.scrollPane().getVerticalScrollBar().getValue());
		});

		// A deposit that empties most of the bank cannot reach that far down any more: the bottom of what is
		// left is as near as the place gets - never the top.
		onEdt(() ->
		{
			listener.onRows(rows(60), listed(60, 60));
			layout(SIDEBAR_WIDTH, 400);
		});
		onEdt(() ->
		{
		});
		onEdt(() ->
		{
			final JScrollBar bar = panel.scrollPane().getVerticalScrollBar();
			assertEquals(60, panel.shownRows());
			assertEquals("clamped to the bottom of the shorter list", bar.getMaximum() - bar.getVisibleAmount(),
				bar.getValue());
			assertTrue("which is not the top", bar.getValue() > 0);
		});
	}

	/**
	 * The half of C32 that survives B107: a list the READER asked for - another window, another ordering or
	 * direction, another gp band - is a DIFFERENT list, so the row they were looking at is not the row that
	 * belongs in that place any more and the top is the new answer. One page, scrolled to the top.
	 */
	@Test
	public void aListTheReaderAskedForStartsAtPageOneAgain() throws Exception
	{
		build();
		publish(rows(600), listed(600, 600));

		// A new window.
		scrollDownTwoPages();
		onEdt(() -> panel.selectWindow(MovementWindow.D7));
		publish(rows(600), status(true, true, 600, 600, MovementWindow.D7, null, STATUS_TEXT, PRICES_AT));
		assertBackAtTheTop("a new window");

		// A new ordering, over the very same rows.
		scrollDownTwoPages();
		onEdt(() -> panel.setSort(SortMode.GP_MOVE, true));
		publish(rows(600), status(true, true, 600, 600, MovementWindow.D7, null, STATUS_TEXT, PRICES_AT));
		assertBackAtTheTop("a new ordering");

		// A new gp band, which the service answers with a shorter list.
		scrollDownTwoPages();
		onEdt(() -> panel.applyBand(1_000_000L, 0L));
		publish(rows(400), status(true, true, 600, 400, MovementWindow.D7, null, STATUS_TEXT, PRICES_AT));
		assertBackAtTheTop("a new gp band");
	}

	/** Opens the second page and scrolls into it, so a reset to the top is visible. */
	private void scrollDownTwoPages() throws Exception
	{
		onEdt(() ->
		{
			panel.showMore();
			layout(SIDEBAR_WIDTH, 400);
			final JScrollBar bar = panel.scrollPane().getVerticalScrollBar();
			assertTrue("the fixture has to be a real, scrollable list: " + bar.getMaximum(), bar.getMaximum() > 1_000);
			bar.setValue(bar.getMaximum() / 2);
			assertTrue("...and to be scrolled into", bar.getValue() > 0);
		});
	}

	private void assertBackAtTheTop(String what) throws Exception
	{
		onEdt(() -> layout(SIDEBAR_WIDTH, 400));
		onEdt(() ->
		{
		});
		onEdt(() ->
		{
			assertEquals(what + ": one page again", BankPriceMovementPanel.ROWS_PER_PAGE, panel.shownRows());
			assertEquals(what + ": and at the top of it", 0, panel.scrollPane().getVerticalScrollBar().getValue());
		});
	}

	// ---- buttons and lifecycle

	@Test
	public void activateAndDeactivateDriveTheServiceVisibility() throws Exception
	{
		build();
		onEdt(() -> panel.onActivate());
		verify(service).setVisible(true);
		onEdt(() -> panel.onDeactivate());
		verify(service).setVisible(false);
	}

	@Test
	public void stopRemovesTheListenerClearsTheRowsAndIgnoresLateCalls() throws Exception
	{
		build();
		publish(rows(300), listed(300, 300));
		onEdt(() -> panel.stop());
		verify(service).removeListener(listener);
		assertTrue(panel.stopped());
		assertTrue(panel.rowPanels().isEmpty());
		assertEquals(0, panel.shownRows());
		assertEquals(0, panel.totalRows());
		assertFalse(panel.showMoreVisible());
		assertEquals("All items", panel.bandTarget().getText());

		publish(rows(5), listed(5, 5));
		assertTrue("a late publish is dropped", panel.rowPanels().isEmpty());
		onEdt(() -> panel.onActivate());
		onEdt(() -> panel.refreshNow());
		verify(service, never()).setVisible(anyBoolean());
		verify(service, never()).refreshNow();

		// AA1: and so is a late fold call, by either road - the settings page's and the band button's alike. A
		// write without a change is a stored choice nobody made.
		onEdt(() ->
		{
			panel.setFoldOpen(true);
			panel.pressFold(true);
			panel.toggleFold();
		});
		assertFalse("a late fold call changes nothing", panel.foldOpen());
		assertTrue("...and writes nothing", prefs.foldSaves.isEmpty());
	}

	// ---- for the bridge

	@Test
	public void shotComponentsAreTheHeaderAndTheBody() throws Exception
	{
		build();
		List<Component> shot = panel.shotComponents();
		assertEquals(2, shot.size());
		assertSame(panel.header(), shot.get(0));
		assertTrue("the login card while nothing is listed", texts(shot.get(1)).contains(BankPriceMovementPanel.LOGIN_TEXT));

		publish(rows(2), listed(2, 2));
		shot = panel.shotComponents();
		assertSame(panel.header(), shot.get(0));
		assertSame("the scroll pane's VIEW, not the scroll pane", panel.listView(), shot.get(1));
		assertSame(panel.listView(), panel.scrollPane().getViewport().getView());
	}

	/** N section 3 §8 and O3: the old fields in the old order and places, the synthesised bankMove, the hero switches. */
	@Test
	public void describeCarriesTheOldFieldsAndTheNewOnes() throws Exception
	{
		build();
		publish(rows(3), status(true, true, 3, 3, MovementWindow.D1, null, "Prices \"14:05\"", 1L));
		onEdt(() -> panel.clickSort(SortMode.GP_MOVE));
		final String d = panel.describe();
		assertFalse(d, d.contains("\n"));
		assertTrue(d, d.startsWith("{\"card\":\"LIST\",\"shown\":3,\"total\":3,\"window\":\"D1\",\"sort\":\"GP_MOVE\",\"descending\":true,\"gpMin\":0,\"gpMax\":0,\"minInvalid\":false,\"maxInvalid\":false,\"status\":\"Prices \\\"14:05\\\"\",\"showing\":false"));
		// M4/M5/O3: the switches, then the card as drawn - in the header (a bank is loaded), the total, and the old
		// line 2 synthesised for the lit window (the mocked status carries no summary, so the total is 0 and 1d has
		// no baseline: one dash on the gp, nothing on the percent). The bank time is before the epoch, so the clock
		// in the footnote is a dash.
		assertTrue(d, d.contains(",\"options\":{\"cash\":true,\"untradeables\":false,\"live\":true"
			+ ",\"inventory\":true,\"hover\":false}"
			+ ",\"live\":{\"fetchedAt\":0,\"latestItems\":0,\"liveRows\":0,\"guideRows\":0,\"alchRows\":0"
			+ ",\"liveDay\":null,\"windowDays\":{\"1d\":null,\"7d\":null,\"30d\":null,\"90d\":null,\"180d\":null}}"));
		assertTrue(d, d.contains(",\"hero\":{\"value\":true,\"gp\":true,\"pct\":true},\"bankValueShowing\":true,\"bankValue\":\"0\",\"bankMove\":\"1d   -   -\",\"bankWindow\":\"1d\",\"heroGp\":\"-\",\"heroPct\":\"\",\"heroSub\":\"Guide prices - bank -\""));
		// S4: the update line rides beside heroSub, because it is the line under it - and it is the live sentence
		// here, because this panel is drawing the plugin's defaults and T1 turns live prices on.
		assertTrue(d, d.contains(",\"heroSub\":\"Guide prices - bank -\",\"updateLine\":\"Live prices on - thin items daily\","));
		// W3: sortLabel is the lit COLUMN as the button prints it, and V2's sortHint is gone - the qualifiers it
		// described went with addendum N's six named orderings, and the direction is the descending field above.
		// Z4: the reader's three quick bands and the four chip TEXTS ride with the fold, between it and the problem
		// row - the numbers so a script can assert what was stored, the labels so it can assert what a shot reads.
		assertTrue(d, d.contains(",\"sortLabel\":\"gp change\",\"countText\":\"All items\",\"bandOn\":false,\"foldOpen\":false"
			+ ",\"presets\":[100000,1000000,10000000],\"presetLabels\":[\"All\",\"100k+\",\"1m+\",\"10m+\"]"
			+ ",\"problemText\":\"\",\"problemRed\":false,\"showMoreText\":\"\"}"));
		assertFalse(d, d.contains("sortHint"));

		publish(rows(3), listedWith(summary()));
		final String withSummary = panel.describe();
		assertTrue(withSummary, withSummary.contains("\"bankValue\":\"1.23b\",\"bankMove\":\"1d   +12.4m   +1.0%\",\"bankWindow\":\"1d\",\"heroGp\":\"+12.4m\",\"heroPct\":\"+1.0%\",\"heroSub\":\"1d vs 07 Sep - bank "));

		onEdt(() -> panel.applyHeroVisibility(HeroVisibility.of(true, false, true)));
		final String noGp = panel.describe();
		assertTrue(noGp, noGp.contains("\"hero\":{\"value\":true,\"gp\":false,\"pct\":true},\"bankValueShowing\":true,\"bankValue\":\"1.23b\",\"bankMove\":\"1d   +12.4m   +1.0%\",\"bankWindow\":\"1d\",\"heroGp\":\"\",\"heroPct\":\"+1.0%\""));

		// Q7: the view switches ride beside the hero's, written from ViewOptions.asMap() in the same shape - five
		// of them since addendum AO took "holding" out from between the untradeables and the live switch, in that
		// value's own order, with the hover switch last.
		onEdt(() -> panel.applyOptions(new ViewOptions(false, true, true, true, false)));
		assertTrue(panel.describe(), panel.describe().contains(
			",\"options\":{\"cash\":false,\"untradeables\":true,\"live\":true,\"inventory\":true,\"hover\":false}"));
		assertFalse("AO1: and the key it echoed is gone", panel.describe().contains("\"holding\":"));
		onEdt(() -> panel.applyOptions(LIVE_OFF.withCountInventory(false)));
		assertTrue(panel.describe(), panel.describe().contains(
			",\"options\":{\"cash\":true,\"untradeables\":false,\"live\":false,\"inventory\":false,\"hover\":false}"
				+ ",\"live\":{"));
		assertTrue(panel.describe(), panel.describe().contains(
			",\"updateLine\":\"Item prices update every 24hrs\","));

		// T8: state.live is what the traded feeds delivered for the publish on screen, beside the switch that asked
		// for them - the five figures under their own names, and 0 everywhere while the switch is off.
		final PriceService.Status withLive = listedWith(summary());
		when(withLive.live()).thenReturn(new PriceService.Status.LiveStatus(1_788_959_760_000L, 4_312, 123, 396, 20));
		publish(rows(3), withLive);
		assertTrue(panel.describe(), panel.describe().contains(
			",\"live\":{\"fetchedAt\":1788959760000,\"latestItems\":4312,\"liveRows\":123,\"guideRows\":396,"
				+ "\"alchRows\":20,\"liveDay\":null,\"windowDays\":{\"1d\":null,\"7d\":null,\"30d\":null,\"90d\":null,"
				+ "\"180d\":null}},\"hero\":{"));

		// U4: and beside them the live CALENDAR - the day the snapshot belongs to and the day each window's traded
		// bucket holds - as ISO dates, with a window that has no bucket printed as a JSON null rather than dropped.
		final PriceService.Status withDays = listedWith(summary());
		final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
		days.put(MovementWindow.D1, LocalDate.of(2026, 9, 11));
		days.put(MovementWindow.D7, LocalDate.of(2026, 9, 5));
		when(withDays.live()).thenReturn(new PriceService.Status.LiveStatus(1_788_959_760_000L, 4_312, 123, 396, 20,
			LocalDate.of(2026, 9, 12), days));
		publish(rows(3), withDays);
		assertTrue(panel.describe(), panel.describe().contains(
			",\"alchRows\":20,\"liveDay\":\"2026-09-12\",\"windowDays\":{\"1d\":\"2026-09-11\",\"7d\":\"2026-09-05\","
				+ "\"30d\":null,\"90d\":null,\"180d\":null}},\"hero\":{"));
	}

	// ---- the width rule, measured (N6)

	/**
	 * N6: every header row and every row measures at most 213 px with the widest figures the formatters can
	 * produce - a 12-digit total, "-99.9%", a "logged out" footnote, the sort column "gp change" and its arrow
	 * beside a stated band, "180d" in its cell, the problem sentence, the fold with both fields filled, a "2,147m" price with a
	 * stack of 28,000 and "+100.0%", and a 30-character name cut with "...". Swing clips a too-wide row silently,
	 * so the rule is measured rather than trusted; the figures are printed for the record.
	 */
	@Test
	public void everyHeaderRowAndEveryRowFitsTheContentWidth() throws Exception
	{
		prefs.stored = new RowFilter(123_456_789L, 2_000_000_000L, SortMode.GP_MOVE, false, MovementWindow.D1);
		buildWithHovers();
		final PriceService.Status wide = status(false, true, 99_999, 99_999, MovementWindow.D1, UNAVAILABLE, STATUS_TEXT, PRICES_AT);
		when(wide.portfolio()).thenReturn(hugeSummary());
		publish(wideRows(), wide);
		onEdt(() ->
		{
			panel.toggleFold();
			assertTrue(panel.heroShowing());
			assertTrue(panel.problemShowing());
			final StringBuilder report = new StringBuilder("header rows at " + W + " px:");
			int i = 0;
			for (Component row : panel.header().getComponents())
			{
				final int width = row.getPreferredSize().width;
				report.append("\n  row ").append(i++).append(' ').append(row.getClass().getSimpleName()).append(' ').append(width).append(" px");
				assertTrue("header row " + (i - 1) + " asks for " + width + " px, more than " + W, width <= W);
				if (row == panel.hero())
				{
					final int inner = BankPriceMovementPanel.CARD_INNER;
					for (Component line : ((Container) row).getComponents())
					{
						final int lw = line.getPreferredSize().width;
						report.append(" [").append(line.getClass().getSimpleName()).append(' ').append(lw).append(']');
						assertTrue("a hero line asks for " + lw + " px, more than the card's " + inner, lw <= inner);
					}
				}
			}
			report.append("\n  widest: ").append(Widgets.widest(panel.header())).append(" px");
			assertTrue(Widgets.widest(panel.header()) <= W);

			// The chips: each label's text inside its cell, at the face the lit chip is painted in.
			final int cell = BankPriceMovementPanel.CHIP_WIDTH;
			for (MovementWindow w : MovementWindow.values())
			{
				final JLabel chip = panel.windowChip(w);
				final int tw = textWidth(chip, Widgets.sansBold(13));
				report.append("\n  chip ").append(chip.getText()).append(' ').append(tw).append(" of ").append(cell);
				assertTrue(chip.getText() + " needs " + tw + " px of its " + cell + " px cell", tw + 2 <= cell);
				assertEquals(cell, chip.getPreferredSize().width);
			}
			for (int p = 0; p < 4; p++)
			{
				final int tw = textWidth(panel.presetCell(p), Widgets.sansBold(13));
				assertTrue(panel.presetCell(p).getText() + " needs " + tw, tw + 2 <= BankPriceMovementPanel.PRESET_WIDTH);
			}

			// The control row: the sort button whole, the band button fitted into what it leaves. Addendum AM
			// swapped which END each stands at (band WEST, sort EAST) and neither fitting rule moved with
			// them, which is why this stays a sum of the two widths and takes no view of the order.
			final int control = panel.sortButton().getPreferredSize().width + BankPriceMovementPanel.ROW_GAP + panel.bandTarget().getPreferredSize().width;
			report.append("\n  control row content ").append(control).append(" px of ").append(W - 2 * BankPriceMovementPanel.ROW_GAP)
				.append(" [").append(panel.sortButton().getText()).append("] [").append(panel.bandTarget().getText()).append(']');
			assertTrue(control <= W - 2 * BankPriceMovementPanel.ROW_GAP);
			assertEquals("gp change", panel.sortButton().getText());
			assertEquals("123m - 2b", panel.bandTarget().getText());
			assertFalse(panel.bandTarget().getText().endsWith(Widgets.ELLIPSIS));

			// The card: nothing cut where it must not be.
			assertFalse("the total is whole", panel.totalLabel().getText().endsWith(Widgets.ELLIPSIS));
			assertEquals("-99.9%", panel.pctLabel().getText());
			assertFalse(panel.deltaLabel().getText().endsWith(Widgets.ELLIPSIS));
			assertEquals("the fixture is logged out, so the clock gives way", "1d vs 31 Dec - logged out", panel.footnoteLabel().getText());
			assertEquals("the fields are 90 px", BankPriceMovementPanel.FIELD_WIDTH, panel.minField().getPreferredSize().width);
			assertEquals("123,456,789", panel.minField().getText());
			assertEquals("2,000,000,000", panel.maxField().getText());
			assertFitted(panel.problemLabel(), UNAVAILABLE);
			assertTrue(panel.problemLabel().getPreferredSize().width <= W);

			// The rows: 213 wide, every line inside, the long name cut with the whole name in the tooltip.
			final List<MovementRowPanel> rowPanels = panel.rowPanels();
			assertEquals(3, rowPanels.size());
			for (MovementRowPanel row : rowPanels)
			{
				assertEquals(row.row().name(), MovementRowPanel.ROW_WIDTH, row.getPreferredSize().width);
				assertEquals(W, row.getPreferredSize().width);
				report.append("\n  row [").append(row.nameText()).append("] ").append(row.getPreferredSize().width).append(" px");
			}
			assertTrue("the widest row column line asks for " + Widgets.widest(panel.rowsColumn()), Widgets.widest(panel.rowsColumn()) <= W);
			final MovementRowPanel longName = rowPanels.get(0);
			assertTrue(longName.nameText(), longName.nameText().endsWith(Widgets.ELLIPSIS));
			assertTrue(longName.tooltipHtml(), longName.tooltipHtml().contains("Karambwan vessel (baited) long"));
			System.out.println(report);
		});
	}

	/**
	 * The Q4 row format, measured on a published page: every cell is the 62 px three-line card the user
	 * approved, and the two gp figures stand in ONE right-aligned column - the stack's directly over the item's,
	 * and every row's over every other row's.
	 *
	 * <p><b>That column is the reason the format is three lines rather than one</b>, and nothing else in this
	 * file pins it. {@link #everyHeaderRowAndEveryRowFitsTheContentWidth} measures a row's OUTER width and takes
	 * no view of where anything inside it lands; {@code priceText()} and {@code gpText()} are asked for their
	 * words and never for their position. A fit order that sized each figure against its own row's left-hand
	 * text would pass every one of those and draw a ragged list, which is the reading failure the whole
	 * three-line cell exists to avoid.
	 *
	 * <p>The fixture is deliberately uneven - "+200m" over "+100m", "+28k" over "+1", "+21k" over "+3.0k" - so a
	 * column that came from the text rather than from a fixed box could not come out straight by accident. It
	 * also pins the compact thousands the format needed ("+3.0k", where the sidebar printed "+3,000" before),
	 * because that figure is the one real loss of precision on the face and a silent return to the long form
	 * would take the column with it.
	 *
	 * <p>Two of the four rows hold ONE item, which is Q4.7: the working still prints, so every row reads the
	 * same way, and the figure beside it does not, because it is already standing on the line above. So those
	 * rows carry one visible gp figure and the other two carry two.
	 *
	 * <p>Measured after a real layout and in the ROWS COLUMN's own coordinates, so "one column" means across the
	 * page the reader scans and not merely inside each card.
	 */
	@Test
	public void everyRowIsTheThreeLineCellAndBothGpFiguresStandInOneColumn() throws Exception
	{
		assertEquals("the format the user approved is a 62 px cell", 62, MovementRowPanel.ROW_HEIGHT);
		final List<MovementRow> uneven = Arrays.asList(
			new MovementRow(1, "Twisted bow", 2, false, 1_200_000_000L, 1_100_000_000L, 100_000_000L, 9.09d,
				2_400_000_000L, null),
			new MovementRow(2, "Feather", 28_000, true, 3L, 2L, 1L, 50.0d, 84_000L, null),
			new MovementRow(3, "Divine ranging potion(3)", 7, true, 32_300L, 29_300L, 3_000L, 10.23d, 226_100L, null),
			new MovementRow(4, "Abyssal whip", 1, false, 1_500_000L, 1_800_000L, -300_000L, -16.66d, 1_500_000L, null));
		build();
		publish(uneven, listed(uneven.size(), uneven.size()));
		onEdt(() ->
		{
			// Laid out at the sidebar's own size, tall enough for four rows with room to spare: a page that had
			// to scroll would take the gutter off every card and measure something else.
			layout(SIDEBAR_WIDTH, 900);
			assertFalse("four rows do not scroll", panel.scrollPane().getVerticalScrollBar().isVisible());

			final List<MovementRowPanel> rows = panel.rowPanels();
			assertEquals(uneven.size(), rows.size());
			final StringBuilder report = new StringBuilder("the Q4 gp column, in the rows column's coordinates:");
			final List<String> texts = new ArrayList<>();
			final List<Integer> edges = new ArrayList<>();
			final List<Integer> widths = new ArrayList<>();
			for (MovementRowPanel row : rows)
			{
				final String why = row.row().name() + ": ";
				assertEquals(why + "the cell is the three-line card", MovementRowPanel.ROW_HEIGHT,
					row.getPreferredSize().height);
				assertEquals(why + "...and the page gave it exactly that", MovementRowPanel.ROW_HEIGHT,
					row.getHeight());

				final List<JLabel> figures = gpFiguresOf(row);
				assertEquals(why + "Q4.7: a stack of one prints its move once, a bigger stack twice",
					row.row().quantity() > 1 ? 2 : 1, figures.size());
				for (JLabel figure : figures)
				{
					final Rectangle where = SwingUtilities.convertRectangle(figure.getParent(),
						figure.getBounds(), panel.rowsColumn());
					texts.add(figure.getText());
					edges.add(where.x + where.width);
					widths.add(where.width);
					report.append("\n  [").append(row.row().name()).append("] \"").append(figure.getText())
						.append("\" right edge ").append(where.x + where.width)
						.append(", box ").append(where.width).append(" px");
				}
			}
			System.out.println(report);

			// The words first, so a failure of the column says whether the figures themselves changed.
			assertEquals("the stack's move over the item's, on every row that has both",
				Arrays.asList("+200m", "+100m", "+28k", "+1", "+21k", "+3.0k", "-300k"), texts);
			// ...then the one thing the words cannot say: they all end on the same pixel.
			for (int i = 1; i < edges.size(); i++)
			{
				assertEquals("\"" + texts.get(i) + "\" does not end where \"" + texts.get(0) + "\" does"
					+ report, edges.get(0), edges.get(i));
				assertEquals("\"" + texts.get(i) + "\" is not in the same box" + report,
					widths.get(0), widths.get(i));
			}
			assertTrue("the column is on the row, not off its left edge", edges.get(0) > 0);
		});
	}

	/**
	 * The gp figures a reader can see on one row's face: the stack's on line 2 and the item's on line 3.
	 *
	 * <p>Found by COLOUR rather than by walking the face's nested {@code BorderLayout}s, exactly as
	 * {@link #gpLabelOf} is found by its text: addendum AL painted those two labels - and nothing else on the
	 * card - in {@link MovementRowPanel#quietChangeColor}, since the name and the stack total are white, the
	 * working is grey, and the percentage beside them keeps the full FIGURE colour. (The word "Total" stood on
	 * line 2 until addendum AO line AO2 deleted it; it was LIGHT_GRAY and never answered this search.) A row
	 * with no move at all must not be asked, because QUIET and FIGURE agree at a signum of zero and the
	 * percentage would answer too.
	 *
	 * <p>An EMPTY figure is left out: a stack of one carries the label with no text on it (Q4.7), and what is
	 * measured here is what is drawn.
	 */
	private static List<JLabel> gpFiguresOf(MovementRowPanel row)
	{
		final Color quiet = MovementRowPanel.quietChangeColor(row.row());
		final List<JComponent> parts = new ArrayList<>();
		descendants(row, parts);
		final List<JLabel> out = new ArrayList<>();
		for (JComponent c : parts)
		{
			if (c instanceof JLabel && quiet.equals(c.getForeground()) && !((JLabel) c).getText().isEmpty())
			{
				out.add((JLabel) c);
			}
		}
		return out;
	}

	/**
	 * N5 / O7: the headless proof - the Ticker sidebar painted to build/ at 225 x 900 with every figure shown, and
	 * again with all three hidden; both carry the orange accent and the fixture's red falls, and they differ.
	 *
	 * <p>Q7 / R5 add a third picture with the view switches on, which is the only one that is NOT 900 px tall: its
	 * fixture has three rows the other two do not, and 900 px hold fourteen ({@link LookRenderer#height}). Since
	 * AA3 it is taller again by the open fold's own height, which is what the header took from the list.
	 *
	 * <p>All four open on the price fold (AA3), so all four differ from the addendum W pictures by the fold's band
	 * and nothing else; the shift itself is measured in
	 * {@link #theOpenFoldShiftsThePictureDownByItsOwnHeightAndChangesNothingElse}.
	 */
	@Test
	public void lookRendererPaintsTheTickerAndTheHiddenCardIntoBuild() throws Exception
	{
		final File dir = new File(System.getProperty("bpm.lookDir", "build"));
		final List<BufferedImage> images = new ArrayList<>();
		final Map<String, ViewOptions> wanted = new LinkedHashMap<>();
		wanted.put(LookRenderer.TICKER_FILE, null);
		wanted.put(LookRenderer.HIDDEN_FILE, null);
		wanted.put(LookRenderer.OPTIONS_FILE, LookRenderer.OPTIONS);
		// T8: the fourth picture, the only one drawn with the live switch on.
		wanted.put(LookRenderer.LIVE_FILE, LookRenderer.LIVE);
		for (Map.Entry<String, ViewOptions> e : wanted.entrySet())
		{
			final HeroVisibility shown = LookRenderer.HIDDEN_FILE.equals(e.getKey())
				? HeroVisibility.NONE : HeroVisibility.ALL;
			// The two acceptance shots go through the renderer's OLD entry point, which addendum Q left alone; the
			// third and fourth are the ones that ask for switches. That entry point draws the guide-only sidebar
			// (T8), which is what those two are compared against byte for byte.
			final ViewOptions options = e.getValue() == null ? LookRenderer.GUIDE_ONLY : e.getValue();
			final File png = e.getValue() == null
				? LookRenderer.write(dir, e.getKey(), shown) : LookRenderer.write(dir, e.getKey(), shown, options);
			assertEquals(e.getKey(), png.getName());
			assertTrue(png.getAbsolutePath(), png.isFile() && png.length() > 1_000L);
			final BufferedImage image = ImageIO.read(png);
			assertEquals(LookRenderer.WIDTH, image.getWidth());
			// The two acceptance shots keep the 900 px they are compared at - the open fold (AA3) eats 57 px of
			// the bare ground under their last row and no more; the options picture is a row pitch taller for
			// addendum R's fifteenth row (R5) and the fold's height taller again, because it is the one whose
			// list had no ground to give.
			assertEquals(e.getKey(), LookRenderer.height(options), image.getHeight());
			final int orange = count(image, ColorScheme.BRAND_ORANGE);
			final int cardGrey = count(image, ColorScheme.DARKER_GRAY_COLOR);
			final int reddish = countReddish(image);
			System.out.println(shown + " " + options + " -> " + png.getAbsolutePath() + ": orange " + orange
				+ " px, card grey " + cardGrey + " px, red " + reddish + " px");
			assertTrue(shown + ": the lit chip's orange is there", orange > 40);
			assertTrue(shown + ": the cards are there", cardGrey > 20_000);
			assertTrue(shown + ": the fixture's falls paint red", reddish > 40);
			images.add(image);
		}
		assertTrue("the hidden card is a different picture", differ(images.get(0), images.get(1)));
		assertTrue("and so is the options picture", differ(images.get(0), images.get(2)));
		assertTrue("and so is the live one", differ(images.get(0), images.get(3)));
		assertEquals(12, LookRenderer.rows().size());
		assertEquals(LookRenderer.rows().size(), LookRenderer.rowNames().size());
		assertEquals(LookRenderer.VALUE_NOW, LookRenderer.summary().valueNow());
		assertEquals("Dragon claws", LookRenderer.rowNames().get(0));

		// Q7 / R5: the third picture is the twelve rows plus three untradeable stacks - two at their alch value
		// and the Crystal body at what its three seeds are worth - and all three are in the sums.
		assertEquals(12, LookRenderer.rows(LookRenderer.GUIDE_ONLY).size());
		assertEquals(15, LookRenderer.rows(LookRenderer.OPTIONS).size());
		final PortfolioSummary counted = LookRenderer.summary(LookRenderer.OPTIONS);
		assertEquals(LookRenderer.VALUE_NOW + 62_000L + LookRenderer.CRYSTAL_BODY_NOW, counted.valueNow());
		assertEquals(LookRenderer.BANK_ITEMS + 3, counted.itemsTotal());
		assertEquals("R3: the parts row is priced, the two alch rows are not", LookRenderer.BANK_ITEMS + 1,
			counted.itemsPriced());
		for (WindowMove move : counted.moves().values())
		{
			assertEquals(move.window().name() + ": the parts row has a baseline, so every window covers it",
				LookRenderer.BANK_ITEMS + 1, move.itemsCovered());
		}

		// R5: it sits among the movers - a parts row is an ordinary priced row - two rows above the alch pair,
		// and it is the only PARTS row in the picture.
		final List<MovementRow> listed = LookRenderer.rows(LookRenderer.OPTIONS);
		assertEquals("Crystal body", listed.get(11).name());
		assertEquals(MovementRow.PriceSource.PARTS, listed.get(11).source());
		assertEquals(16_694_766L, listed.get(11).unitPrice().longValue());
		assertEquals("Untradeable - valued as its parts: 3 x Crystal armour seed",
			MovementRowPanel.partsLine(listed.get(11)));
		assertEquals(MovementRow.PriceSource.ALCH, listed.get(13).source());
		assertEquals(MovementRow.PriceSource.ALCH, listed.get(14).source());

		// T8: the fourth picture's fixture is the same twelve rows in the same order - three of them priced off the
		// traded series (one of which fell back to the guide for its 1 d window, T4) and one left on the guide with
		// the check that refused it - and the switch OFF leaves the list exactly as it was.
		final List<MovementRow> live = LookRenderer.rows(LookRenderer.LIVE);
		assertEquals(12, live.size());
		int liveCount = 0;
		for (MovementRow r : live)
		{
			if (r.isLive())
			{
				liveCount++;
			}
		}
		assertEquals(LookRenderer.LIVE_STACKS, liveCount);
		assertEquals(MovementRow.PriceSource.LIVE, live.get(0).source());
		assertEquals(MovementRow.PriceSource.LIVE, live.get(0).windowSource(MovementWindow.D1));
		assertEquals("the whip prints the live mid and compares the guide's two ends (T4)",
			MovementRow.PriceSource.GUIDE, live.get(1).windowSource(MovementWindow.D1));
		assertEquals(LookRenderer.WHIP_LIVE_NOW, live.get(1).unitPrice().longValue());
		assertEquals("Green hat", live.get(5).name());
		assertEquals("Guide price - live not used: 12 traded yesterday", MovementRowPanel.liveRefusalLine(live.get(5)));
		assertEquals(LookRenderer.LIVE_STACKS, LookRenderer.summary(LookRenderer.LIVE).liveRows());
		assertEquals("the guide-only list is untouched by any of it", LookRenderer.rows(),
			LookRenderer.rows(LookRenderer.GUIDE_ONLY));
		assertEquals(0, LookRenderer.summary(LookRenderer.GUIDE_ONLY).liveRows());

		// U3: the fourth picture is drawn down the LIVE path with a real live calendar behind it - three live stacks
		// on LIVE_DAY - and that calendar is chosen to agree with the fixture's guide days, so the footnote reads
		// "1d vs 08 Sep" either way and the picture still differs from the guide-only one in the card's last line and
		// four rows' numbers alone. The three guide-only pictures carry no live calendar at all.
		assertEquals(LookRenderer.THEN_DAY, LookRenderer.liveDays().get(MovementWindow.D1));
		for (WindowMove move : LookRenderer.summary().moves().values())
		{
			assertEquals(move.window().name() + ": the two calendars agree on this fixture", move.thenDay(),
				LookRenderer.liveDays().get(move.window()));
		}
		assertEquals("the live row records the day it compared against", LookRenderer.THEN_DAY,
			live.get(0).windowDay(MovementWindow.D1));
		assertEquals("and the window that fell back records the guide's", LookRenderer.THEN_DAY,
			live.get(1).windowDay(MovementWindow.D1));
		assertNull("the guide-only fixtures are untouched", LookRenderer.status(LookRenderer.GUIDE_ONLY).live());
		final PriceService.Status.LiveStatus liveFixture = LookRenderer.status(LookRenderer.LIVE).live();
		assertEquals(LookRenderer.LIVE_STACKS, liveFixture.liveRows());
		assertEquals(LookRenderer.LIVE_DAY, liveFixture.liveDay());
		onEdt(() ->
		{
			final BankPriceMovementPanel drawn = LookRenderer.build(HeroVisibility.ALL, LookRenderer.LIVE);
			assertTrue(drawn.footnoteLabel().getText(),
				drawn.footnoteLabel().getText().startsWith("1d vs 08 Sep - bank "));
		});
	}

	// ---- the fixes of the 2026-09-09 hardening pass

	/**
	 * B045: the two FIGURES of a fall take the lifted red (5.05:1 where the constant measures 3.63:1 on the card
	 * grey); the direction MARKS - the triangle and the card's coloured edge - keep the constant, so a 23 px
	 * glyph is not lightened for a threshold it already clears and the row rails still match the card.
	 */
	@Test
	public void fallingFiguresTakeTheLiftedRedAndTheMarksKeepTheConstant() throws Exception
	{
		build();
		publish(rows(3), listedWith(summary()));
		onEdt(() ->
		{
			panel.selectWindow(MovementWindow.D30);
			assertEquals(Widgets.MOVE_DOWN_TEXT, panel.deltaLabel().getForeground());
			assertEquals(Widgets.MOVE_DOWN_TEXT, panel.pctLabel().getForeground());
			assertTrue("the lift is a lift", Widgets.MOVE_DOWN_TEXT.getRed() >= ColorScheme.PROGRESS_ERROR_COLOR.getRed());
			assertTrue(sameIcon(Widgets.triangleDown(ColorScheme.PROGRESS_ERROR_COLOR), panel.triangleLabel().getIcon()));
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(), edgeOf(panel.hero()));

			// A rise and a flat window are untouched.
			panel.selectWindow(MovementWindow.D1);
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, panel.deltaLabel().getForeground());
			panel.selectWindow(MovementWindow.D7);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.deltaLabel().getForeground());
		});
		final PortfolioSummary s = summary();
		assertEquals(Widgets.MOVE_DOWN_TEXT, BankPriceMovementPanel.moveTextColor(s.move(MovementWindow.D30)));
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, BankPriceMovementPanel.moveTextColor(s.move(MovementWindow.D1)));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, BankPriceMovementPanel.moveTextColor(null));
		assertEquals("the mark rule is untouched", ColorScheme.PROGRESS_ERROR_COLOR,
			BankPriceMovementPanel.moveColor(s.move(MovementWindow.D30)));
	}

	/**
	 * B038: the header is pinned outside the scroll pane, so the list - and only the list - loses width when the
	 * scrollbar appears. The header reserves the same gutter, so its cards and the row cards under them end on
	 * the same pixel whether the list scrolls or not, and neither is ever squeezed under the 213 px the design
	 * is measured at.
	 */
	@Test
	public void theHeaderReservesTheSameGutterTheScrollingListLoses() throws Exception
	{
		build();
		final int outer = net.runelite.client.ui.PluginPanel.PANEL_WIDTH + net.runelite.client.ui.PluginPanel.SCROLLBAR_WIDTH;
		publish(rows(40), listedWith(summary()));
		onEdt(() ->
		{
			layout(outer, 400);
			assertTrue("the fixture has to actually scroll", panel.scrollPane().getVerticalScrollBar().isVisible());
			assertTrue("the gutter is measured off the bar, never assumed", panel.gutter() > 0);
			final int hero = panel.hero().getWidth();
			final int row = panel.rowPanels().get(0).getWidth();
			assertTrue("card " + hero + " px against row " + row + " px", Math.abs(hero - row) <= 1);
			assertTrue("neither may fall under the design width: " + hero + " / " + row, hero >= W && row >= W);
		});

		publish(rows(2), listedWith(summary()));
		onEdt(() ->
		{
			layout(outer, 900);
			assertFalse("no bar for two rows", panel.scrollPane().getVerticalScrollBar().isVisible());
			assertEquals("and the gutter is handed back", 0, panel.gutter());
			final int hero = panel.hero().getWidth();
			final int row = panel.rowPanels().get(0).getWidth();
			assertTrue("card " + hero + " px against row " + row + " px", Math.abs(hero - row) <= 1);
			assertTrue(hero >= W && row >= W);
		});
	}

	/**
	 * B039: a publish that adds or drops the problem row must not take the OPEN price fold out of the header.
	 * It used to: the header was rebuilt wholesale, which tore the focused gp field out of the hierarchy, and
	 * the field's focus-lost handler then applied whatever half of "100k" had been typed as the filter.
	 */
	@Test
	public void aStatusPublishNeverTakesTheOpenFoldOutOfTheHeader() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() -> panel.toggleFold());
		assertTrue(panel.foldOpen());

		final List<Component> removed = new ArrayList<>();
		onEdt(() -> panel.header().addContainerListener(new java.awt.event.ContainerAdapter()
		{
			@Override
			public void componentRemoved(java.awt.event.ContainerEvent e)
			{
				removed.add(e.getChild());
			}
		}));

		publish(rows(3), listedWithProblem(COOLDOWN, MovementWindow.D1, THEN_DAY));
		assertEquals(Arrays.asList(panel.hero(), panel.controlRow(), panel.fold(), panel.problemLabel()),
			Arrays.asList(panel.header().getComponents()));
		publish(rows(3), listed(3, 3));
		assertEquals(Arrays.asList(panel.hero(), panel.controlRow(), panel.fold()),
			Arrays.asList(panel.header().getComponents()));

		assertEquals("only the problem row moved: " + removed, Arrays.asList(panel.problemLabel()), removed);
		assertFalse("the fold stayed in place", removed.contains(panel.fold()));
		assertFalse("...and so did the card", removed.contains(panel.hero()));
	}

	/**
	 * B016: the manager's sprite cache holds 128 images and a page is 250, so a rebuild that asked it again for
	 * every row would miss on nearly all of them and queue that many sprite renders onto the GAME thread - once
	 * per bank change. Each key is asked for once; a quantity change is a new key, because the stack number is
	 * drawn into the picture.
	 */
	@Test
	public void everySpriteIsAskedForOnceHoweverOftenTheListIsRebuilt() throws Exception
	{
		// A manager that answers with a picture, as the real one does - and nothing for one id, to pin that a
		// picture that could not be given is NOT remembered as an absence.
		when(itemManager.getImage(anyInt(), anyInt(), anyBoolean())).thenAnswer(call ->
			(int) call.getArgument(0) == 3 ? null
				: new AsyncBufferedImage(null, MovementRowPanel.ICON_WIDTH, MovementRowPanel.ICON_HEIGHT,
					BufferedImage.TYPE_INT_ARGB));
		build();
		publish(rows(3), listed(3, 3));
		publish(new ArrayList<>(rows(3)), status(true, true, 3, 3, MovementWindow.D1, null, STATUS_TEXT, PRICES_AT,
			THEN_DAY.plusDays(1)));
		publish(new ArrayList<>(rows(3)), status(true, true, 3, 3, MovementWindow.D7, null, STATUS_TEXT, PRICES_AT,
			THEN_DAY.plusDays(2)));
		assertEquals("three rebuilds", 3, panel.rebuilds());
		verify(itemManager, times(1)).getImage(1, 1, false);
		verify(itemManager, times(1)).getImage(2, 2, true);
		verify(itemManager, times(3)).getImage(3, 3, true);

		// The same item at a NEW quantity is a new picture: the stack number is drawn into the sprite, so an
		// id-only memory would paint yesterday's count after a deposit.
		final List<MovementRow> deposited = new ArrayList<>(rows(3));
		deposited.set(1, new MovementRow(2, "Item 2", 9, true, 2_000L, 1_900L, 100L, 5.2d, 18_000L, null));
		publish(deposited, listed(3, 3));
		verify(itemManager, times(1)).getImage(2, 9, true);
		verify(itemManager, times(1)).getImage(2, 2, true);
		assertEquals(9, panel.rowPanels().get(1).row().quantity());
	}

	/**
	 * B098: while the sidebar is showing another panel, a publish is REMEMBERED, not built - design D8's
	 * "nothing while nobody is looking" applied to the EDT and the game thread, not only to the network. The
	 * last one is replayed whole when the reader comes back.
	 */
	@Test
	public void aPublishWhileTheSidebarIsHiddenIsDeferredUntilItIsShownAgain() throws Exception
	{
		build();
		onEdt(() -> panel.onActivate());
		publish(rows(3), listed(3, 3));
		assertEquals(1, panel.rebuilds());

		onEdt(() -> panel.onDeactivate());
		publish(rows(6), listed(6, 6));
		publish(rows(9), listed(9, 9));
		assertEquals("nothing was built for a panel nobody can see", 1, panel.rebuilds());
		assertEquals(3, panel.rowPanels().size());
		verify(itemManager, never()).getImage(9, 9, true);

		onEdt(() -> panel.onActivate());
		assertEquals("the last publish, replayed whole", 2, panel.rebuilds());
		assertEquals(9, panel.rowPanels().size());
		assertEquals(9, panel.totalRows());
		verify(itemManager, times(1)).getImage(9, 9, true);
	}

	/**
	 * B043 / B103 as addendum P line P2 finished it: an ACCEPTED refresh answers in two beats. Guide prices change
	 * once a day, so the figures usually come back identical and the control looked dead - the only reply it ever
	 * gave was the red cooldown line earned by tapping it again. "Refreshing..." says the tap was taken;
	 * "Up to date" says the work finished; and it fades on its own after a minute, so the card goes back to its
	 * minimal face without anyone tidying up after it (the user's own wish).
	 *
	 * <p>Driven by the timer hooks, never by waiting: {@link BankPriceMovementPanel#fireRefreshAck()} is what the
	 * 1.8 s timer calls and {@link BankPriceMovementPanel#fireUpToDate()} what the 60 s one does.
	 */
	@Test
	public void theRefreshLinkGoesRefreshingThenUpToDateThenBackToRefresh() throws Exception
	{
		buildWithHovers();
		publish(rows(1), listedWith(summary()));
		onEdt(() ->
		{
			assertEquals(BankPriceMovementPanel.REFRESH_TEXT, panel.refreshLabel().getText());
			press(panel.refreshLabel());
			assertTrue(panel.refreshAcknowledging());
			assertEquals(BankPriceMovementPanel.REFRESHING_TEXT, panel.refreshLabel().getText());
			assertEquals("the tooltip still says what the control does", BankPriceMovementPanel.REFRESH_TIP,
				panel.refreshLabel().getToolTipText());
			// The caption row is a fixed 191 px and the acknowledgement is the longer word: measure it.
			assertTrue(panel.captionRow().getPreferredSize().width + " px of " + BankPriceMovementPanel.CARD_INNER,
				panel.captionRow().getPreferredSize().width <= BankPriceMovementPanel.CARD_INNER);

			// A publish does not wipe it - the one that follows a refresh lands within a frame or two.
			panel.applyHeroVisibility(HeroVisibility.ALL);
			assertTrue(panel.refreshAcknowledging());

			// Beat two: the same face, the same grey, still clickable - and its own fit.
			final Font face = panel.refreshLabel().getFont();
			final Color grey = panel.refreshLabel().getForeground();
			panel.fireRefreshAck();
			assertEquals("the word addendum P asks for, pinned", "Up to date", BankPriceMovementPanel.UP_TO_DATE_TEXT);
			assertEquals(BankPriceMovementPanel.UP_TO_DATE_TEXT, panel.refreshLabel().getText());
			assertTrue(panel.upToDateShowing());
			assertFalse(panel.refreshAcknowledging());
			assertEquals("the same face", face, panel.refreshLabel().getFont());
			assertEquals("the same grey - it is not a failure", grey, panel.refreshLabel().getForeground());
			assertEquals(BankPriceMovementPanel.REFRESH_TIP, panel.refreshLabel().getToolTipText());
			assertTrue(panel.captionRow().getPreferredSize().width + " px of " + BankPriceMovementPanel.CARD_INNER,
				panel.captionRow().getPreferredSize().width <= BankPriceMovementPanel.CARD_INNER);
			assertEquals("the minute the user asked for", 60_000, BankPriceMovementPanel.UP_TO_DATE_MILLIS);

			// Beat three: the fade takes it back to the word the control started with.
			panel.fireUpToDate();
			assertEquals(BankPriceMovementPanel.REFRESH_TEXT, panel.refreshLabel().getText());
			assertFalse(panel.upToDateShowing());
			assertFalse(panel.refreshTimersRunning());
		});
		verify(service, times(1)).refreshNow();

		// A tap during "Up to date" restarts the whole sequence rather than shortening it.
		onEdt(() ->
		{
			press(panel.refreshLabel());
			panel.fireRefreshAck();
			assertTrue(panel.upToDateShowing());
			press(panel.refreshLabel());
			assertEquals(BankPriceMovementPanel.REFRESHING_TEXT, panel.refreshLabel().getText());
			panel.fireRefreshAck();
			assertTrue(panel.upToDateShowing());
		});
		verify(service, times(3)).refreshNow();

		// The menu entry is the same control and answers the same way; stop() takes the word off AND stops both
		// timers, so a panel the client has removed leaves nothing ticking (P2).
		onEdt(() ->
		{
			item(panel.heroMenu(), BankPriceMovementPanel.REFRESH_MENU_TEXT).doClick();
			assertTrue(panel.refreshAcknowledging());
			assertTrue(panel.refreshTimersRunning());
			panel.stop();
			assertEquals(BankPriceMovementPanel.REFRESH_TEXT, panel.refreshLabel().getText());
			assertFalse("shutDown stops both timers", panel.refreshTimersRunning());

			// ...and a timer event already queued when stop() ran cannot put the word back on a dead panel.
			panel.fireRefreshAck();
			assertEquals(BankPriceMovementPanel.REFRESH_TEXT, panel.refreshLabel().getText());
			assertFalse(panel.refreshTimersRunning());
		});
	}

	/**
	 * P2: a REFUSED tap - one inside the service's 30 s cooldown - leaves the link alone. The panel is never told
	 * which tap was served ({@code PriceService.refreshNow()} is void and answers a refusal by publishing the red
	 * cooldown line, K5/L10), so the link answers the GESTURE and the problem row answers the OUTCOME; nothing
	 * different is painted on the link, and the word is true either way, because a tap refused for being inside
	 * 30 s of the last one is a tap made when the prices really are up to date.
	 */
	@Test
	public void aRefusedRefreshAnswersInTheProblemRowAndLeavesTheLinkAlone() throws Exception
	{
		build();
		publish(rows(1), listedWith(summary()));
		onEdt(() -> press(panel.refreshLabel()));
		publish(rows(1), listedWithProblem(COOLDOWN, MovementWindow.D1, THEN_DAY));
		onEdt(() ->
		{
			assertEquals("the red line is the answer to a refusal", COOLDOWN, panel.problemLabel().getText());
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, panel.problemLabel().getForeground());
			assertTrue(panel.problemShowing());
			assertEquals("and the link is running its own sequence, untouched",
				BankPriceMovementPanel.REFRESHING_TEXT, panel.refreshLabel().getText());
			panel.fireRefreshAck();
			assertEquals(BankPriceMovementPanel.UP_TO_DATE_TEXT, panel.refreshLabel().getText());
			assertEquals("nothing red on the link", ColorScheme.LIGHT_GRAY_COLOR, panel.refreshLabel().getForeground());
			assertEquals("the cooldown line is still the one saying what happened", COOLDOWN, panel.problemLabel().getText());
		});
	}

	/**
	 * B069: the ONE test that hands the panel a status the service could really publish. Every other test here
	 * mocks {@code Status} deliberately (see {@link StatusFixtures}), which means no other test would notice a
	 * getter whose real answer differs from a mock's default - and two of them already do: the real
	 * {@code portfolio()} coerces null to EMPTY, and the real {@code text()} IS the problem sentence when there
	 * is one, so the join in {@code statusText()} is a defensive path a real status cannot reach.
	 */
	@Test
	public void aRealStatusFromTheServiceRendersTheWholeHeader() throws Exception
	{
		buildWithHovers();
		final PriceService.Status real = StatusFixtures.listed(PRICES_AT, PRICES_AT - 60_000L, MovementWindow.D1,
			null, THEN_DAY, summary(), 538, 519);
		assertEquals("the fixture is a real baseline day", THEN_DAY, real.thenDay());
		publish(rows(3), real);

		assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());
		assertEquals("1.23b", panel.totalLabel().getText());
		assertEquals("+12.4m", panel.deltaLabel().getText());
		assertEquals("1d vs 07 Sep - bank " + MovementMath.formatTime(real.bankAtMillis()),
			panel.footnoteLabel().getText());
		assertEquals("a real status draws the same hover a mocked one does (AF)", VALUE_TIP,
			panel.hero().getToolTipText());
		assertEquals("no problem: the sentence is the header line", real.text(), panel.statusText());
		assertFalse(panel.problemShowing());

		// ...and with a problem, the real class answers it from text() too, so the panel prints it once.
		final PriceService.Status problem = StatusFixtures.listed(PRICES_AT, PRICES_AT - 60_000L, MovementWindow.D1,
			UNAVAILABLE, THEN_DAY, summary(), 538, 519);
		publish(rows(3), problem);
		assertEquals(UNAVAILABLE, panel.statusText());
		assertEquals(UNAVAILABLE, panel.problemLabel().getToolTipText());
		assertTrue(panel.problemShowing());
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, panel.problemLabel().getForeground());
		assertEquals("a problem is the problem row's sentence; the hover is still the figure alone (AF)",
			VALUE_TIP, panel.hero().getToolTipText());

		// The coercion the mock cannot show: a real status with no summary answers EMPTY, never null.
		final PriceService.Status none = StatusFixtures.listed(PRICES_AT, PRICES_AT - 60_000L, MovementWindow.D1,
			null, THEN_DAY, null, 0, 0);
		assertSame(PortfolioSummary.EMPTY, none.portfolio());
		publish(Collections.emptyList(), none);
		assertEquals("0", panel.totalLabel().getText());
		assertEquals("a bank worth nothing still answers a figure, never a blank hover", "0 gp",
			panel.hero().getToolTipText());
	}

	/**
	 * B073: the window chips and the fold presets are FIXED cells with centred text and no ellipsis - they clip
	 * outright rather than truncating - and every measurement in this file is taken through the logical
	 * {@code Font.DIALOG}, which maps to a different physical face on Linux and macOS than on the machine the
	 * acceptance shots are taken on. So the design is measured with 15 % of headroom: a face that wide still
	 * leaves "180d" and "100k+" inside their cells.
	 */
	@Test
	public void everyFixedCellSurvivesAWiderFace() throws Exception
	{
		build();
		onEdt(() ->
		{
			final Font wider = Widgets.sansBold(15);
			for (MovementWindow w : MovementWindow.values())
			{
				final JLabel chip = panel.windowChip(w);
				final int tw = textWidth(chip, wider);
				assertTrue(chip.getText() + " needs " + tw + " px of its " + BankPriceMovementPanel.CHIP_WIDTH
					+ " px cell at a 15 % wider face", tw + 2 <= BankPriceMovementPanel.CHIP_WIDTH);
			}
			for (int p = 0; p < BankPriceMovementPanel.PRESET_COUNT; p++)
			{
				final int tw = textWidth(panel.presetCell(p), wider);
				assertTrue(panel.presetCell(p).getText() + " needs " + tw + " px of "
					+ BankPriceMovementPanel.PRESET_WIDTH, tw + 2 <= BankPriceMovementPanel.PRESET_WIDTH);
			}
		});
	}

	/**
	 * B046: the three message cards are the first screen a new user ever sees. {@link
	 * net.runelite.client.ui.components.PluginErrorPanel} sets no face on its title (so it inherits the look and
	 * feel's 16 px bitmap RuneScape default) and the small RuneScape face in {@code Color.GRAY} on its
	 * description - so the panel would introduce itself in one typeface and then work in another.
	 */
	@Test
	public void theThreeMessageCardsAreInThePanelsOwnType() throws Exception
	{
		build();
		assertMessageCardType(panel.shotComponents().get(1), BankPriceMovementPanel.LOGIN_TEXT);
		publish(Collections.emptyList(), status(true, false, 0, 0, MovementWindow.D1, null, "No bank yet", 0L));
		assertMessageCardType(panel.shotComponents().get(1), BankPriceMovementPanel.NO_BANK_TEXT);
		publish(Collections.emptyList(), listed(0, 0));
		assertMessageCardType(panel.shotComponents().get(1), BankPriceMovementPanel.NO_TRADEABLES_TEXT);
	}

	/**
	 * B064: the bank container event that fills this list is sent when the bank interface OPENS (and again on
	 * every deposit and withdrawal), so the reader watches the list fill with the bank on screen. The card used
	 * to tell them it would happen when they CLOSED it.
	 */
	@Test
	public void theNoBankCardSaysTheListFillsWhenTheBankOpens() throws Exception
	{
		build();
		publish(Collections.emptyList(), status(true, false, 0, 0, MovementWindow.D1, null, "No bank yet", 0L));
		final List<String> lines = texts(panel.shotComponents().get(1));
		assertTrue(lines.toString(), lines.contains(BankPriceMovementPanel.NO_BANK_TEXT));
		final String description = lines.get(lines.size() - 1);
		assertTrue(description, description.contains("as soon as you open your bank"));
		assertFalse("the old wording described something the plugin does not do", description.contains("close the bank"));
	}

	/**
	 * B047: every menu item in this sidebar sets its own face. One that does not is drawn by the look and feel
	 * in the 16 px bitmap RuneScape default, and the card's menu would then look like another plugin's beside
	 * the order menu one control row away.
	 */
	@Test
	public void everyMenuItemInTheSidebarSetsItsOwnFace() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() ->
		{
			final String family = Widgets.sans(12).getFamily();
			for (JMenuItem entry : items(panel.heroMenu()))
			{
				assertNotNull(entry.getText() + " has no font of its own", entry.getFont());
				assertEquals(entry.getText(), family, entry.getFont().getFamily());
				assertEquals(entry.getText(), 12f, entry.getFont().getSize2D(), 0f);
			}
			for (JMenuItem entry : items(panel.buildSortMenu()))
			{
				assertEquals(family, entry.getFont().getFamily());
				assertEquals(12f, entry.getFont().getSize2D(), 0f);
			}
		});
	}

	/**
	 * B051: the band button says in its tooltip what its face has no room for - the COUNT of what is listed,
	 * which addendum N's deleted count line otherwise left nowhere on the panel.
	 *
	 * <p>B048's other half, the sort button's tooltip, is no longer a sentence of its own: addendum X made it
	 * the one phrase pinned by {@link #theSortButtonsHoverIsOnePhraseUnderEveryColumnAndSwitch}, and what a
	 * press will do moved onto the menu entries (X1).
	 */
	@Test
	public void theBandTooltipCarriesTheCount() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listed(10, 3));
		onEdt(() ->
		{
			assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText()
				.startsWith(BankPriceMovementPanel.BAND_TIP));
			assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText().endsWith("3 of 10 items"));

			panel.applyBand(100_000L, 0L);
			assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText()
				.startsWith("Showing items from 100k"));
			assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText().endsWith("3 of 10 items"));
		});
		publish(rows(1), listed(1, 1));
		assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText().endsWith("1 item"));
	}

	/**
	 * X2 with Q6: nothing in the control row follows a VIEW switch. The gp column's tooltip was the one that
	 * did ("gp change per item" / "per stack", W2, over the row switch addendum AO has since deleted), which is
	 * why {@code applyOptions} had to repaint the control row when a switch moved; the button now says one
	 * phrase whatever the switches are, the four entries say the same two sentences (W3, X1), and the LABELS
	 * never moved in the first place.
	 *
	 * <p>Addendum AO1 settles the question that hover used to ask: the gp column compares the STACK's move for
	 * everyone, under no switch at all, so there is no second reading left for a qualifier to name.
	 */
	@Test
	public void noHoverInTheControlRowFollowsAViewSwitch() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listed(10, 3));
		onEdt(() ->
		{
			panel.setSort(SortMode.GP_MOVE, true);
			assertEquals("Change sorting", panel.sortButton().getToolTipText());
			assertEquals("gp change", item(panel.buildSortMenu(), "gp change").getText());
			assertEquals("Toggle gp change", item(panel.buildSortMenu(), "gp change").getToolTipText());
			assertEquals("Sort by Stack price", item(panel.buildSortMenu(), "Stack price").getToolTipText());

			// A switch moves and the control row does not: same column, same face, same hovers, same menu.
			panel.applyOptions(hovers(ViewOptions.DEFAULT.withLivePrices(false)));
			assertEquals("gp change", panel.sortButton().getText());
			assertEquals("Change sorting", panel.sortButton().getToolTipText());
			assertEquals("no qualifier in the menu, under either switch (W3)", "gp change",
				item(panel.buildSortMenu(), "gp change").getText());
			assertEquals("Toggle gp change", item(panel.buildSortMenu(), "gp change").getToolTipText());
			assertEquals("Sort by Stack price", item(panel.buildSortMenu(), "Stack price").getToolTipText());
			assertEquals("Sort by Item price", item(panel.buildSortMenu(), "Item price").getToolTipText());
			assertEquals("Sort by Percent change", item(panel.buildSortMenu(), "Percent change").getToolTipText());

			// The other three columns read the same under every switch, as they always did.
			panel.setSort(SortMode.STACK_VALUE, false);
			assertEquals("Change sorting", panel.sortButton().getToolTipText());
			assertEquals("Toggle Stack price", item(panel.buildSortMenu(), "Stack price").getToolTipText());
			panel.applyOptions(hovers(ViewOptions.DEFAULT));
			assertEquals("Change sorting", panel.sortButton().getToolTipText());
			assertEquals("Toggle Stack price", item(panel.buildSortMenu(), "Stack price").getToolTipText());

			// ...and a switch that was never read leaves the row exactly as it was.
			panel.applyOptions(hovers(ViewOptions.DEFAULT.withCountCash(false)));
			assertEquals("Change sorting", panel.sortButton().getToolTipText());
			assertEquals("Stack price", panel.sortButton().getText());
		});
	}

	/**
	 * AO1, and the whole point of the change: with the "gp change" column LIT and biggest first, a big stack of
	 * a small mover outranks a single item that moved more per unit.
	 *
	 * <p>{@code holdingOnRows} used to decide that. Off - the shipped default - the column compared
	 * {@link MovementRow#deltaGp()}, one item's move, and a hundred Robin hood hats that each gained 50 gp sat
	 * below one Twisted bow that gained 2,000: the reader asking "what gained me the most gp" was answered with
	 * a per-item league table. Addendum AO deleted the switch and settled the column on
	 * {@link MovementRow#holdingDeltaGp()} for everyone, because this is a portfolio tracker and the row's
	 * headline line has been the STACK since addendum AN. <b>It is a user-visible change to the default
	 * ordering of one column</b>, which is why it is pinned here rather than left to follow from the comparator.
	 *
	 * <p>It is asserted through the PANEL's own control - the button is clicked, and the filter that click hands
	 * the service is the one the ordering is taken from - so a future change that quietly re-aims the gp column
	 * cannot pass by leaving the button and the comparator disagreeing. The two fixture rows are built so the
	 * two readings disagree outright, and that they do is asserted rather than assumed.
	 */
	@Test
	public void theGpColumnRanksTheBigStackOfASmallMoverFirst() throws Exception
	{
		// 100 hats at 32,000 that each gained 50 gp: the stack gained 5,000. One bow at 1,000,000 that gained
		// 2,000: the stack gained 2,000, because there is one of it.
		final MovementRow hats = new MovementRow(1, "Robin hood hat", 100, false, 32_000L, 31_950L, 50L,
			50.0 * 100 / 31_950, 3_200_000L, null);
		final MovementRow bow = new MovementRow(2, "Twisted bow", 1, false, 1_000_000L, 998_000L, 2_000L,
			2_000.0 * 100 / 998_000, 1_000_000L, null);
		assertTrue("the fixture really does split the two readings: the bow moved more per item",
			bow.deltaGp() > hats.deltaGp());
		assertTrue("...and the hats moved more as a stack", hats.holdingDeltaGp() > bow.holdingDeltaGp());

		build();
		publish(Arrays.asList(hats, bow), listed(2, 2));
		onEdt(() -> panel.clickSort(SortMode.GP_MOVE));
		assertEquals(SortMode.GP_MOVE, panel.filter().sort());
		assertTrue("a column lights biggest first (W3)", panel.filter().descending());
		assertEquals("gp change", panel.sortButton().getText());

		final List<MovementRow> lit = MovementMath.apply(Arrays.asList(hats, bow), panel.filter());
		assertEquals("AO1: the stack's move is the key, under no switch at all",
			Arrays.asList("Robin hood hat", "Twisted bow"), names(lit));

		// And the arrow still means what it says: the smallest stack move leads when it points the other way.
		onEdt(() -> panel.clickSort(SortMode.GP_MOVE));
		assertFalse("the lit column picked again flips the direction (W3)", panel.filter().descending());
		assertEquals(Arrays.asList("Twisted bow", "Robin hood hat"),
			names(MovementMath.apply(Arrays.asList(hats, bow), panel.filter())));
	}

	/** The names of {@code rows} in the order they were handed over, for an ordering assertion to read. */
	private static List<String> names(List<MovementRow> rows)
	{
		final List<String> out = new ArrayList<>(rows.size());
		for (MovementRow row : rows)
		{
			out.add(row.name());
		}
		return out;
	}

	/**
	 * W3: {@code describe().sortLabel} is the lit column as the button prints it and {@code descending} is the
	 * arrow beside it, so the live list's {@code bpm state | jq -r '.result.filter | .sort, .descending'} and
	 * {@code .result.panel.sortLabel} prove the whole control row without a picture - and {@code sortHint} is
	 * gone, so a script that still asks for it gets a JSON null rather than a stale qualifier.
	 */
	@Test
	public void describeCarriesTheColumnTheSortButtonPrintsAndNoHint() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() ->
		{
			for (SortMode column : SortMode.values())
			{
				panel.setSort(column, true);
				assertTrue(panel.describe(), panel.describe().contains("\"sortLabel\":\"" + column.label() + "\","));
				assertEquals(column.label(), panel.sortButton().getText());
				assertFalse(panel.describe(), panel.describe().contains("sortHint"));
			}

			// A view switch moves no label, so it moves no sortLabel either (and since X2, no hover). The one
			// that came nearest to moving this label was the row switch, and addendum AO1 deleted it.
			panel.setSort(SortMode.GP_MOVE, true);
			assertTrue(panel.describe(), panel.describe().contains("\"sortLabel\":\"gp change\","));
			panel.applyOptions(ViewOptions.DEFAULT.withLivePrices(false));
			assertTrue(panel.describe(), panel.describe().contains("\"sortLabel\":\"gp change\","));
			assertFalse(panel.describe(), panel.describe().contains("sortHint"));
		});
	}

	/**
	 * B105 (checker): a stack the guide table cannot price is dropped by {@code MovementMath.apply} BEFORE the
	 * band is considered, so a newly released item vanishes from the list with no band set - and addendum N
	 * deleted the count line that would have shown the shortfall. The band tooltip names them.
	 */
	@Test
	public void theBandTooltipNamesTheStacksWithNoGuidePrice() throws Exception
	{
		buildWithHovers();
		publish(rows(3), listedWith(summary()));
		final String tip = panel.bandTarget().getToolTipText();
		assertTrue(tip, tip.endsWith("3 of 538 items, 19 with no guide price"));

		// Nothing to explain when every stack is priced: the clause is absent, not "0 with no guide price".
		final PortfolioSummary allPriced = new PortfolioSummary(1_000L, 3, 3, new EnumMap<>(MovementWindow.class));
		publish(rows(3), listedWith(allPriced));
		final String clean = panel.bandTarget().getToolTipText();
		assertTrue(clean, clean.endsWith("3 of 538 items"));
		assertFalse(clean, clean.contains("guide price"));
	}

	/**
	 * B005 / B101 (checker): {@code PriceService} publishes {@code degraded} + {@code degradedReason} for the two
	 * states the figures on screen cannot show - the last history fetch failed so the baselines are whatever was
	 * stored (L11), and too few stacks compare for the anchor day to be derived, so the wiki's newest table
	 * stands in as "now" (L3). Until this, the only readers in the tree were the dev bridge and the tests, so the
	 * sidebar was pixel-identical whether the wiki answered five minutes ago or has failed all session. The
	 * FOOTNOTE carries the provenance, so the footnote reddens; the whole sentence goes under the figure on the
	 * card's hover, which has no 191 px budget - and it is the one thing addendum AF left there beside the
	 * figure, for exactly that reason. The problem row is deliberately untouched - L11 pins {@code problem()}
	 * null while a stored baseline stands.
	 */
	@Test
	public void aDegradedStatusRedensTheFootnoteAndPutsTheReasonUnderTheFigure() throws Exception
	{
		buildWithHovers();
		final String reason = "the last wiki history fetch failed - showing stored baselines";
		final PriceService.Status blind = listedWith(summary());
		when(blind.degraded()).thenReturn(true);
		when(blind.degradedReason()).thenReturn(reason);
		publish(rows(3), blind);

		assertEquals("running blind is the one thing the figures cannot say",
			ColorScheme.PROGRESS_ERROR_COLOR, panel.footnoteLabel().getForeground());
		assertTrue(panel.footnoteLabel().getText(), panel.footnoteLabel().getText().startsWith("1d vs 07 Sep - bank "));
		assertNull("L11: a window with a stored baseline still never complains", panel.status().problem());
		assertFalse("the problem row stays out - the warning is on the card", panel.problemShowing());
		// AF: the figure, a break, the sentence - on the total and on every other child of the card alike.
		assertEquals("<html>" + VALUE_TIP + "<br>" + reason + "</html>", panel.totalLabel().getToolTipText());
		assertEquals(panel.totalLabel().getToolTipText(), panel.hero().getToolTipText());

		// ...and a confident status takes both back off, leaving the figure on its own.
		publish(rows(3), listedWith(summary()));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.footnoteLabel().getForeground());
		assertEquals(VALUE_TIP, panel.totalLabel().getToolTipText());
	}

	/** B049: the Show-more tooltip counts the rows it will really build - every bank's last click is a short one. */
	@Test
	public void theShowMoreTooltipCountsTheRowsItWillBuild() throws Exception
	{
		buildWithHovers();
		publish(rows(262), listed(262, 262));
		onEdt(() ->
		{
			assertEquals("Show 12 more", panel.showMoreLabel().getText());
			assertEquals("Build the next 12 rows", panel.showMoreLabel().getToolTipText());
		});
		publish(rows(600), listed(600, 600));
		onEdt(() ->
		{
			assertEquals("Show 350 more", panel.showMoreLabel().getText());
			assertEquals("Build the next 250 rows", panel.showMoreLabel().getToolTipText());
		});
	}

	/**
	 * B019: both config paths are posted with {@code invokeLater} by the plugin, so one can be drained after
	 * shutDown has removed this panel and dropped its listener. They carry the same guard as onRows.
	 */
	@Test
	public void aConfigChangeQueuedBeforeStopIsIgnoredAfterIt() throws Exception
	{
		build();
		publish(rows(3), listed(3, 3));
		onEdt(() -> panel.stop());

		onEdt(() -> panel.applyFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D90).withGpMin(5_000L)));
		assertEquals("a late filter repaint is dropped", RowFilter.DEFAULT, panel.filter());
		onEdt(() -> panel.applyHeroVisibility(HeroVisibility.NONE));
		assertEquals("...and so is a late hero repaint", HeroVisibility.ALL, panel.heroVisibility());
		assertTrue(prefs.saves.isEmpty());
		assertTrue(prefs.heroSaves.isEmpty());
	}

	// ---- helpers

	/** Sizes and lays the panel out top-down, twice: the second pass sees the scrollbar the first one created. */
	private void layout(int width, int height)
	{
		panel.setSize(width, height);
		LookRenderer.layoutTree(panel);
		LookRenderer.layoutTree(panel);
	}

	/** Every label of a message card is in the sidebar's own family, and its description is the readable grey. */
	private static void assertMessageCardType(Component card, String title)
	{
		final List<JComponent> all = new ArrayList<>();
		descendants((Container) card, all);
		final String family = Widgets.sans(12).getFamily();
		int labels = 0;
		for (JComponent c : all)
		{
			if (!(c instanceof JLabel) || ((JLabel) c).getText().isEmpty())
			{
				continue;
			}
			final JLabel label = (JLabel) c;
			labels++;
			assertEquals(label.getText(), family, label.getFont().getFamily());
			if (!label.getText().contains(title))
			{
				assertEquals("the description reads in the sidebar's grey, not Color.GRAY",
					ColorScheme.LIGHT_GRAY_COLOR, label.getForeground());
			}
		}
		assertEquals(title + ": a title and a description", 2, labels);
	}

	/** The viewer's calendar day of an instant - the zone the bank stamp and the clock are both read in. */
	private static LocalDate localDay(long millis)
	{
		return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	/** Every label text under a component, for reading a message card or a chip row. */
	private static List<String> texts(Component c)
	{
		final List<String> out = new ArrayList<>();
		if (c instanceof JLabel)
		{
			out.add(((JLabel) c).getText());
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				out.addAll(texts(child));
			}
		}
		return out;
	}

	private static void descendants(Container root, List<JComponent> out)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JComponent)
			{
				out.add((JComponent) c);
			}
			if (c instanceof Container)
			{
				descendants((Container) c, out);
			}
		}
	}

	private static List<JMenuItem> items(JPopupMenu menu)
	{
		final List<JMenuItem> out = new ArrayList<>();
		for (Component c : menu.getComponents())
		{
			if (c instanceof JMenuItem)
			{
				out.add((JMenuItem) c);
			}
		}
		return out;
	}

	private static List<String> itemTexts(JPopupMenu menu)
	{
		final List<String> out = new ArrayList<>();
		for (JMenuItem item : items(menu))
		{
			out.add(item.getText());
		}
		return out;
	}

	/** The menu item whose text (HTML or plain) starts with {@code label} after the {@code <html>} tag. */
	private static JMenuItem item(JPopupMenu menu, String label)
	{
		for (JMenuItem item : items(menu))
		{
			final String text = item.getText().startsWith("<html>") ? item.getText().substring(6) : item.getText();
			if (text.startsWith(label))
			{
				return item;
			}
		}
		throw new AssertionError("no menu item " + label + " in " + itemTexts(menu));
	}

	/** A mouse press delivered to every listener on {@code c} - what a real click starts with. */
	private static void press(JComponent c)
	{
		final MouseEvent e = new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON1);
		for (MouseListener l : c.getMouseListeners())
		{
			l.mousePressed(e);
		}
	}

	/** The RIGHT button going down on {@code c}: the start of the hero card's menu gesture, never a press. */
	private static void rightPress(JComponent c)
	{
		final MouseEvent e = new MouseEvent(c, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 1, 1, 1, false, MouseEvent.BUTTON3);
		for (MouseListener l : c.getMouseListeners())
		{
			l.mousePressed(e);
		}
	}

	/** The mouse entering or leaving {@code c}, delivered to every listener. */
	private static void hover(JComponent c, boolean entered)
	{
		final MouseEvent e = new MouseEvent(c, entered ? MouseEvent.MOUSE_ENTERED : MouseEvent.MOUSE_EXITED,
			System.currentTimeMillis(), 0, 1, 1, 0, false);
		for (MouseListener l : c.getMouseListeners())
		{
			if (entered)
			{
				l.mouseEntered(e);
			}
			else
			{
				l.mouseExited(e);
			}
		}
	}

	/**
	 * A LEFT click on a row card, delivered to every listener on it - the gesture of addendum AG, which since
	 * addendum AI opens and shuts the cell.
	 *
	 * <p>A click and not a press: the row's listener answers {@code mouseClicked} so that the right button still
	 * belongs entirely to the two K8 menu entries. The EDT, like every other touch of these components, because
	 * the toggle re-pins the row's size and revalidates the column it is in.
	 */
	private static void clickRow(JComponent c)
	{
		final MouseEvent e = new MouseEvent(c, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 1, 1, 1,
			false, MouseEvent.BUTTON1);
		for (MouseListener l : c.getMouseListeners())
		{
			l.mouseClicked(e);
		}
	}

	/**
	 * The description block of a row (addendum AI): the {@link BorderLayout#CENTER} child of the cell, built with
	 * the row and hidden until it is clicked.
	 *
	 * <p>Asked for by its CONSTRAINT rather than by index, because the constraint is the design - the cell is a
	 * face pinned NORTH over a detail block filling what is left, both inside the one card border, which is what
	 * makes an open row one growing cell instead of a second widget under a row that stayed at its shut height.
	 */
	private static JLabel detailOf(MovementRowPanel row)
	{
		final Component c = ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		assertTrue("AI: the cell's CENTER is the description block, not " + c, c instanceof JLabel);
		return (JLabel) c;
	}

	/**
	 * The window the rows on screen were built for - the panel's own rule, restated once here rather than
	 * guessed at by each caller: the STATUS names it whenever it carries one, because a publish can land after
	 * the reader has already moved the filter, and the saved filter names it otherwise ({@code onRows}).
	 *
	 * <p>Needed since addendum AK, which put the window's label into a row's open block ("Change 7d"), so a
	 * test that checks the block has to know which window the page was drawn for.
	 */
	private MovementWindow listWindow()
	{
		final PriceService.Status s = panel.status();
		return s != null && s.window() != null ? s.window() : panel.filter().window();
	}

	/**
	 * The gp figure's label on a row's face, or null when the row has nothing to print there.
	 *
	 * <p>Found by the words on it rather than by walking the face's nested {@code BorderLayout}s: the figure is
	 * the one thing on a row that prints {@code MovementRowPanel.gpText()}, and a search by text survives the
	 * next tidy-up of the fit order, which a path through the layout would not.
	 *
	 * <p>Null, and not a failure, when there is nothing to print - a row with no baseline at all, and a row
	 * whose price has not moved. Q4 kept that blank: the column is a fixed box and holds itself open empty.
	 *
	 * <p>It finds LINE 2's figure, which is what {@code gpText()} returns. Line 3's item figure is a different
	 * string on every stack of more than one, so it cannot be caught by this search; {@link #gpFiguresOf} is
	 * what looks at both.
	 */
	@Nullable
	private static JLabel gpLabelOf(MovementRowPanel row)
	{
		final String text = row.gpText();
		if (text.isEmpty())
		{
			return null;
		}
		final List<JComponent> parts = new ArrayList<>();
		descendants(row, parts);
		JLabel found = null;
		for (JComponent c : parts)
		{
			if (c instanceof JLabel && text.equals(((JLabel) c).getText()))
			{
				assertNull("two labels on one row print \"" + text + "\"", found);
				found = (JLabel) c;
			}
		}
		assertNotNull("no label on the row prints its gp figure \"" + text + "\"", found);
		return found;
	}

	/**
	 * A row standing OPEN (addendum AI): the state the panel records, the block showing, and the cell grown by
	 * exactly what that block asks for.
	 *
	 * <p>All three, because each can hold without the others and only the three together are what a reader sees.
	 * The state alone is the seam; the visibility alone would pass on a cell that never grew, so the description
	 * would be drawn over the row under it; and the height alone would pass on an empty block.
	 *
	 * <p><b>Re-pointed by addendum AK.</b> The text used to be {@code tooltip(...)} re-wrapped, and since AK it
	 * is the labelled column {@link MovementRowPanel#detail} builds - so the expected string is obtained by
	 * CALLING that method with this page's own context rather than by copying its output here. A copy would be
	 * a second implementation of the block, free to drift from the first; a call cannot, and it says what the
	 * panel's promise actually is: an open cell shows the block AK builds for THIS row, under the window and
	 * the switches the page was built with, and nothing else.
	 *
	 * <p>The last argument is the one thing the block cannot work out for itself: the name is repeated above
	 * the table only when the row's face had to CUT it, which is why {@code MovementRowPanel} sets the text
	 * after {@code Widgets.setFittedName} has run. Asked of the label the reader is looking at
	 * ({@link MovementRowPanel#nameText()}), so this agrees with the face by construction.
	 */
	private void assertOpen(String why, MovementRowPanel row)
	{
		assertTrue(why + ": the panel records it open", row.expanded());
		final JLabel detail = detailOf(row);
		assertTrue(why + ": the description is showing", detail.isVisible());
		assertEquals(why + ": AK's labelled block, built for this row under the page's window and switches",
			MovementRowPanel.detail(row.row(), listWindow(), panel.listThenDay(), panel.options(),
				!row.row().name().equals(row.nameText())),
			detail.getText());
		assertEquals(why + ": the cell grew by exactly what the block asks for",
			MovementRowPanel.ROW_HEIGHT + detail.getPreferredSize().height, row.getPreferredSize().height);
		assertTrue(why + ": ...which is more than the 62 px card", row.getPreferredSize().height
			> MovementRowPanel.ROW_HEIGHT);
	}

	/** A row SHUT (addendum AI): the state, the block hidden, and the plain 62 px card of addendum N. */
	private static void assertClosed(String why, MovementRowPanel row)
	{
		assertFalse(why + ": the panel records it shut", row.expanded());
		assertFalse(why + ": the description is hidden", detailOf(row).isVisible());
		assertEquals(why + ": the cell is the 62 px card", MovementRowPanel.ROW_HEIGHT,
			row.getPreferredSize().height);
	}

	/**
	 * Every component of the hero card that carries the CARD's own hover - the {@code heroTipTargets} list inside
	 * the panel, read back through its accessors.
	 *
	 * <p>The gear and the update line are deliberately not among them: each keeps a tooltip of its own.
	 */
	private List<JComponent> heroTipTargets()
	{
		return Arrays.<JComponent>asList(panel.hero(), panel.captionRow(), panel.captionLabel(), panel.totalRow(),
			panel.totalLabel(), panel.moveLine(), panel.triangleLabel(), panel.deltaLabel(), panel.pctLabel(),
			panel.stripHolder(), panel.footnoteLabel());
	}

	/**
	 * AI: no row in the sidebar is showing a hover - on itself, or on any component the pointer can land on
	 * inside it. Every offender is collected before anything fails, so one run names all of them rather than
	 * the first the walk happened to reach.
	 *
	 * <p><b>It takes no view of addendum AJ's hover switch, and that is the point.</b> A row is silent with the
	 * switch on and silent with it off, because since AI a row's description is the block the cell opens rather
	 * than a tooltip, so every caller may hand this any setting it likes.
	 *
	 * <p>Null and not the empty string. Swing's {@code ToolTipManager} registers a component the moment it is
	 * given any text and opens an empty yellow box for {@code ""} - which is the very reading ("a box follows
	 * my pointer down the list") that moving the description into the cell exists to remove.
	 */
	private void assertNoRowHovers(String why)
	{
		final List<String> offenders = new ArrayList<>();
		for (MovementRowPanel row : panel.rowPanels())
		{
			final List<JComponent> parts = new ArrayList<>();
			parts.add(row);
			descendants(row, parts);
			assertFalse("a row has children to be silent about", parts.isEmpty());
			for (JComponent c : parts)
			{
				final String tip = c.getToolTipText();
				if (tip != null)
				{
					offenders.add(row.row().name() + ": " + name(c) + " -> \"" + tip + "\"");
				}
			}
		}
		if (!offenders.isEmpty())
		{
			fail(why + ": " + offenders.size() + " component(s) inside a row still show a hover. A row says"
				+ " nothing on hover since AI - its description is the block the click opens - so"
				+ " MovementRowPanel.clearHovers must reach every child, including the ones"
				+ " Widgets.setFittedName and Widgets.setFitted hang the full text on. Offenders: " + offenders);
		}
	}

	/**
	 * Every component a pointer can land on in this sidebar, mapped to the place it is in.
	 *
	 * <p>The panel's whole tree - which reaches the four CardLayout cards, the header, the fold and every row -
	 * and then the two popup menus, which are children of nothing until Swing shows them and so would be missed
	 * by a walk from the panel alone. The gear menu is built once and kept; the sort menu is rebuilt on every
	 * open (X1), so this asks for a fresh one exactly as a reader's click does.
	 */
	private Map<JComponent, String> everyHoverTarget()
	{
		final Map<JComponent, String> out = new LinkedHashMap<>();
		collectHoverTargets(panel, "the sidebar", out);
		collectHoverTargets(panel.heroMenu(), "the gear menu", out);
		collectHoverTargets(panel.buildSortMenu(), "the sort menu", out);
		return out;
	}

	private static void collectHoverTargets(Container root, String place, Map<JComponent, String> out)
	{
		if (root instanceof JComponent)
		{
			out.put((JComponent) root, place);
		}
		final List<JComponent> parts = new ArrayList<>();
		descendants(root, parts);
		for (JComponent c : parts)
		{
			out.put(c, place);
		}
	}

	/**
	 * What to call a component in a failure message: its class, and the words on its face when it has any.
	 *
	 * <p>The words are the point. "JLabel" names a hundred components in this panel and none of them to a
	 * reader; {@code JLabel "Item prices update every 24hrs"} names exactly one.
	 */
	private static String name(JComponent c)
	{
		String text = null;
		if (c instanceof JLabel)
		{
			text = ((JLabel) c).getText();
		}
		else if (c instanceof AbstractButton)
		{
			text = ((AbstractButton) c).getText();
		}
		else if (c instanceof JTextField)
		{
			text = ((JTextField) c).getText();
		}
		return c.getClass().getSimpleName() + (text == null || text.isEmpty() ? "" : " \"" + text + "\"");
	}

	/**
	 * AH3: nothing in the sidebar is showing a hover. Every offender is collected before anything fails, so one
	 * run names all of them rather than the first one the walk happened to reach.
	 */
	private void assertNothingHovers(String why)
	{
		final List<String> offenders = new ArrayList<>();
		for (Map.Entry<JComponent, String> e : everyHoverTarget().entrySet())
		{
			final String tip = e.getKey().getToolTipText();
			if (tip != null)
			{
				offenders.add(e.getValue() + ": " + name(e.getKey()) + " -> \"" + tip + "\"");
			}
		}
		if (!offenders.isEmpty())
		{
			fail(why + ": " + offenders.size() + " component(s) still show a hover with the switch off."
				+ " Every tooltip in this panel must go through setHover, and a label fitted with"
				+ " Widgets.setFitted must have its text adopted straight afterwards. Offenders: " + offenders);
		}
	}

	/**
	 * AH3: the controls that used to be spared - the ones the user found still talking - are silent.
	 *
	 * <p>Named one by one and not walked, deliberately: {@link #assertNothingHovers} proves the general rule,
	 * and this proves it of exactly the components the reversed scope decision is ABOUT, so a failure here
	 * reads as "the sort button went back to speaking" rather than as a tree walk that found something.
	 */
	private void assertControlTooltipsSilent(String why)
	{
		for (Map.Entry<JComponent, String> e : controlTipTargets().entrySet())
		{
			assertNull(why + ": " + e.getValue() + " (" + name(e.getKey()) + ")", e.getKey().getToolTipText());
		}
	}

	/** The controls whose own words addendum AH spared and addendum AH3 took back, each with its name. */
	private Map<JComponent, String> controlTipTargets()
	{
		final Map<JComponent, String> out = new LinkedHashMap<>();
		out.put(panel.sortButton(), "the sort button");
		out.put(panel.refreshLabel(), "the Refresh link");
		out.put(panel.updateLabel(), "the update line");
		out.put(panel.gearLabel(), "the gear");
		out.put(panel.bandTarget(), "the band button");
		for (MovementWindow w : MovementWindow.values())
		{
			out.put(panel.windowChip(w), "the " + w + " chip");
		}
		out.put(panel.showValueItem(), "the 'Show bank value' item");
		out.put(panel.showGpItem(), "the 'Show change in gp' item");
		out.put(panel.showPctItem(), "the 'Show change in %' item");
		out.put(panel.livePricesItem(), "the 'Use live prices' item");
		out.put(panel.countCashItem(), "the coins item");
		out.put(panel.countUntradeablesItem(), "the untradeables item");
		out.put(panel.countInventoryItem(), "the inventory item");
		out.put(panel.presetRow(), "the preset row");
		out.put(panel.resetPresetsButton(), "the 'Reset to default' button");
		out.put(panel.okButton(), "the OK button");
		out.put(panel.showHoverTextItem(), "the switch's own item");
		return out;
	}

	/**
	 * AH3: with the switch ON, everything in the sidebar that explains a CONTROL says exactly what it said
	 * before the switch existed. Widening WHEN a control speaks is no licence to change WHAT it says, so every
	 * literal here is the one the pre-AH tests pinned.
	 */
	private void assertControlTooltipsIntact(String why)
	{
		assertEquals(why + ": the words addendum X asks for, pinned", "Change sorting",
			BankPriceMovementPanel.SORT_BUTTON_TIP);
		assertEquals(why + ": the sort button", BankPriceMovementPanel.SORT_BUTTON_TIP,
			panel.sortButton().getToolTipText());
		assertEquals(why + ": the Refresh link", BankPriceMovementPanel.REFRESH_TIP,
			panel.refreshLabel().getToolTipText());
		// The update line's own hover (S2, T5): whichever of the two sentences the live switch has it on.
		assertEquals(why + ": the update line",
			BankPriceMovementPanel.updateTooltip(PRICES_AT, panel.options()),
			panel.updateLabel().getToolTipText());
		assertEquals(why + ": the gear", BankPriceMovementPanel.OPTIONS_TIP, panel.gearLabel().getToolTipText());
		assertNotNull(why + ": the band button", panel.bandTarget().getToolTipText());
		for (MovementWindow w : MovementWindow.values())
		{
			assertTrue(why + ": the " + w + " chip", panel.windowChip(w).getToolTipText()
				.startsWith("Guide-price change"));
		}
		// Every gear item that carries a line of its own says it here - the switch's OWN item included. AH's
		// reasoning was that this one had to speak in both states or it would be the one control in the client
		// that explains itself only once it has been turned on; AH3 accepted that cost, because the user asked
		// for "no hover text at all unless it is on" and an exception for the switch is still hover text.
		assertEquals(why, BankPriceMovementPanel.SHOW_VALUE_TIP, panel.showValueItem().getToolTipText());
		assertEquals(why, BankPriceMovementPanel.SHOW_GP_TIP, panel.showGpItem().getToolTipText());
		assertEquals(why, BankPriceMovementPanel.SHOW_PCT_TIP, panel.showPctItem().getToolTipText());
		assertEquals(why, BankPriceMovementPanel.LIVE_PRICES_TIP, panel.livePricesItem().getToolTipText());
		assertEquals(why, BankPriceMovementPanel.COUNT_CASH_TIP, panel.countCashItem().getToolTipText());
		assertEquals(why, BankPriceMovementPanel.COUNT_UNTRADEABLES_TIP,
			panel.countUntradeablesItem().getToolTipText());
		assertEquals(why, BankPriceMovementPanel.COUNT_INVENTORY_TIP, panel.countInventoryItem().getToolTipText());
		assertEquals(why, BankPriceMovementPanel.PRESETS_TIP, panel.presetRow().getToolTipText());
		// AH2 moved this one out of the entries and into the bottom row; its line went with it, unchanged.
		assertEquals(why, BankPriceMovementPanel.RESET_PRESETS_TIP, panel.resetPresetsButton().getToolTipText());
		assertEquals(why, BankPriceMovementPanel.OK_TIP, panel.okButton().getToolTipText());
		assertEquals(why + ": and the switch's own item", BankPriceMovementPanel.SHOW_HOVER_TEXT_TIP,
			item(panel.heroMenu(), BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT).getToolTipText());
	}

	/** How many pixels an icon actually draws: anything not fully transparent (AH's "empty box" is still ink). */
	private static int inked(Icon icon)
	{
		final BufferedImage image = pixels(icon);
		int n = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if (((image.getRGB(x, y) >>> 24) & 0xff) != 0)
				{
					n++;
				}
			}
		}
		return n;
	}

	/** The label shows {@code full} whole, or cut with "..." and the whole text as its tooltip (Widgets.setFitted). */
	private static void assertFitted(JLabel label, String full)
	{
		final String text = label.getText();
		if (!full.equals(text))
		{
			assertTrue(text, text.endsWith(Widgets.ELLIPSIS));
			assertTrue(text, full.startsWith(text.substring(0, text.length() - Widgets.ELLIPSIS.length())));
		}
		assertEquals("the whole sentence is the tooltip", full, label.getToolTipText());
	}

	private static int textWidth(JLabel label, Font font)
	{
		final FontMetrics fm = label.getFontMetrics(font);
		return fm.stringWidth(label.getText());
	}

	/** The colour of the hero card's left edge: the matte border outside {@link Widgets#card}'s padding. */
	private static Color edgeOf(JPanel hero)
	{
		final CompoundBorder border = (CompoundBorder) hero.getBorder();
		return ((MatteBorder) border.getOutsideBorder()).getMatteColor();
	}

	private static int edgeWidthOf(JPanel hero)
	{
		final CompoundBorder border = (CompoundBorder) hero.getBorder();
		return ((MatteBorder) border.getOutsideBorder()).getBorderInsets().left;
	}

	/** Two icons are the same when they paint the same pixels - the drawn glyphs have no equals of their own. */
	private static boolean sameIcon(Icon a, Icon b)
	{
		if (a == null || b == null)
		{
			return a == b;
		}
		if (a.getIconWidth() != b.getIconWidth() || a.getIconHeight() != b.getIconHeight())
		{
			return false;
		}
		return !differ(pixels(a), pixels(b));
	}

	private static BufferedImage pixels(Icon icon)
	{
		final BufferedImage image = new BufferedImage(Math.max(1, icon.getIconWidth()), Math.max(1, icon.getIconHeight()), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			icon.paintIcon(null, g, 0, 0);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	private static boolean differ(BufferedImage a, BufferedImage b)
	{
		if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight())
		{
			return true;
		}
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static int count(BufferedImage image, Color colour)
	{
		final int rgb = colour.getRGB() & 0xffffff;
		int n = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) & 0xffffff) == rgb)
				{
					n++;
				}
			}
		}
		return n;
	}

	/**
	 * Pixels that read as a fall after antialiasing: strongly red, and far enough from its own green and blue to
	 * be neither the orange accent (220, 138, 0) nor a grey. The band takes both reds the sidebar draws - the
	 * rails' {@code PROGRESS_ERROR_COLOR.darker()} (161, 21, 21) and the figures'
	 * {@link Widgets#MOVE_DOWN_TEXT} (240, 92, 84).
	 */
	private static int countReddish(BufferedImage image)
	{
		int n = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				final int rgb = image.getRGB(x, y);
				final int r = (rgb >> 16) & 0xff;
				final int g = (rgb >> 8) & 0xff;
				final int b = rgb & 0xff;
				if (r > 150 && g < 100 && b < 100 && r - g > 120)
				{
					n++;
				}
			}
		}
		return n;
	}

	private static void onEdt(Runnable body) throws Exception
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
