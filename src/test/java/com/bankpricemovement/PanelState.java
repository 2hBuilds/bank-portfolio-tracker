package com.bankpricemovement;

import com.bankpricemovement.PriceService.Status;
import java.awt.Component;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.components.FlatTextField;

/**
 * The sidebar's state as one line of JSON, for the tests and for the developer bridge (a local-only class of the test source set) -
 * the panel's {@code describe()} of the shipped plugin, moved here in release 1.2.2 (the trim) because nothing a
 * player runs reads it. It builds the text by hand, with no Gson and no reflection, from what the panel and the
 * History view hold, read through package-private access: the panel's fields and a handful of its own helpers are
 * package-private for this class and for the tests, with no getter added for it.
 *
 * <p>The shapes did not change in the move - every key, its order and its spelling are the ones the old
 * {@code describe()} printed - so the live recipes in {@code docs/effect-lab.md} read the same.
 */
final class PanelState
{
	/** What separates the three parts of {@link #moveText} ("1d   +12.4m   +1.0%"), kept for the bridge (N §8). */
	static final String VALUE_SEP = "   ";

	private PanelState()
	{
	}

	/**
	 * One line of JSON describing the visible state, for the bridge's {@code state}: the card, the paging,
	 * the filter, the two fields' validity, the status sentence and whether the panel is on screen, with the bank
	 * hold beside it ({@code bank}, addendum AS: {@code open}, {@code pending}, {@code glow}, {@code heldEvents},
	 * {@code reads} - see {@link #bankJson}) - then the
	 * five view switches ({@code options}, Q7, T1, Y1 and 1.0.9 part 3 - {@code holding} is gone with addendum AO and
	 * {@code hover} with release 1.2.0),
	 * beside them what the TRADED feeds delivered for this
	 * publish ({@code live}, T8: {@code fetchedAt}, {@code latestItems}, {@code liveRows}, {@code guideRows},
	 * {@code alchRows}, every figure 0 while the switch is off, then addendum U's {@code liveDay} and
	 * {@code windowDays} - the live calendar this publish counted back from, both null-valued while there is no
	 * snapshot), and the three hero switches ({@code hero}, O3),
	 * each written from its own value in the same shape, the hero as drawn (the old {@code bankMove}
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
	static String describe(final BankPriceMovementPanel panel)
	{
		final PortfolioSummary summary = panel.portfolio();
		final WindowMove move = summary.move(panel.filter.window());
		return "{\"card\":\"" + panel.card
			+ "\",\"shown\":" + panel.shown
			+ ",\"total\":" + panel.rows.size()
			+ ",\"window\":\"" + panel.filter.window().name()
			+ "\",\"sort\":\"" + panel.filter.sort().name()
			+ "\",\"descending\":" + panel.filter.descending()
			+ ",\"gpMin\":" + panel.filter.gpMin()
			+ ",\"gpMax\":" + panel.filter.gpMax()
			+ ",\"search\":" + json(panel.search)
			+ ",\"matching\":" + panel.matching.size()
			+ ",\"minInvalid\":" + isMarkedInvalid(panel.minField)
			+ ",\"maxInvalid\":" + isMarkedInvalid(panel.maxField)
			+ ",\"status\":" + json(statusText(panel))
			+ ",\"showing\":" + panel.isShowing()
			+ ",\"bank\":" + bankJson(panel)
			+ ",\"options\":" + flags(asMap(panel.options))
			+ ",\"live\":" + values(asMap(liveStatus(panel)))
			+ ",\"hero\":" + flags(asMap(panel.heroVisibility))
			+ ",\"bankValueShowing\":" + heroShowing(panel)
			+ ",\"bankValue\":" + json(shows(panel, panel.totalLabel) ? panel.totalLabel.getText() : "")
			+ ",\"bankMove\":" + json(moveText(panel.filter.window(), move))
			+ ",\"bankWindow\":\"" + panel.filter.window().label()
			+ "\",\"heroGp\":" + json(shows(panel, panel.deltaLabel) ? panel.deltaLabel.getText() : "")
			+ ",\"heroPct\":" + json(shows(panel, panel.pctLabel) ? panel.pctLabel.getText() : "")
			+ ",\"heroSub\":" + json(heroSubText(panel))
			+ ",\"updateLine\":" + json(panel.updateLabel.getText())
			+ ",\"sortLabel\":" + json(panel.sortButton.getText())
			+ ",\"countText\":" + json(panel.bandTarget.getText())
			+ ",\"bandOn\":" + panel.bandOn()
			+ ",\"foldOpen\":" + panel.foldOpen
			+ ",\"includeLegacy\":" + panel.includeLegacy
			+ ",\"legacyDays\":" + panel.legacyDaysPresent()
			+ ",\"presets\":" + numbers(panel.presets.mins())
			+ ",\"presetLabels\":" + texts(presetLabels(panel))
			+ ",\"changeText\":" + textJson(panel.itemsSettings.text())
			+ ",\"historyChangeText\":" + textJson(panel.historySettings.text())
			+ ",\"problemText\":" + json(panel.problemText())
			+ ",\"problemRed\":" + panel.isErrorStatus()
			+ ",\"showMoreText\":" + json(showMoreVisible(panel) ? panel.showMoreLabel.getText() : "")
			+ "}";
	}

	/** K7/L7: the header line always opens with this - the prices are the guide prices, whatever else is known. */
	static final String HEADER_PREFIX = "Guide prices";

	/** The sentence a MOCKED status was told to answer ({@link #told}); a mock has no real fields to build it from. */
	private static final Map<Status, String> TOLD = Collections.synchronizedMap(new IdentityHashMap<Status, String>());

	/** Tells the echo what a mocked or spied status answers as its sentence and its header line; returns the status. */
	static Status told(final Status status, final String text)
	{
		TOLD.put(status, text);
		return status;
	}

	/**
	 * The status line (C21, K7): the problem sentence when there is one, otherwise {@link #headerText}. Until release
	 * 1.2.2 this was {@code Status.text()}; no player's sidebar reads it, only the developer bridge's echo and the tests.
	 */
	static String text(final Status status)
	{
		final String told = TOLD.get(status);
		if (told != null)
		{
			return told;
		}
		return status.problem() != null ? status.problem() : headerText(status);
	}

	/**
	 * The header line regardless of any problem (L7): {@code "Guide prices - Bank as of HH:MM"}, and with a baseline
	 * {@code "Guide prices - 1d vs 07 Sep - Bank as of HH:MM"} (the guide DAY, UTC); {@code "No bank yet"} while no
	 * snapshot exists and {@code "(logged out)"} appended while the client is out.
	 */
	static String headerText(final Status status)
	{
		final String told = TOLD.get(status);
		if (told != null)
		{
			return told;
		}
		final StringBuilder sb = new StringBuilder(HEADER_PREFIX);
		final String baseline = baselineText(status);
		if (baseline != null)
		{
			sb.append(" - ").append(baseline);
		}
		sb.append(" - ");
		if (!status.bankLoaded())
		{
			sb.append("No bank yet");
		}
		else
		{
			sb.append("Bank as of ").append(MovementMath.formatTime(status.bankAtMillis()));
			if (!status.loggedIn())
			{
				sb.append(" (logged out)");
			}
		}
		return sb.toString();
	}

	/** The baseline part of the header on its own - {@code "1d vs 07 Sep"} (L7) - or null when the window has none. */
	@Nullable
	static String baselineText(final Status status)
	{
		final LocalDate day = status.thenDay();
		return day == null ? null : status.window().label() + " vs " + MovementMath.formatDay(day);
	}

	/**
	 * The whole status sentence, for the bridge's {@code status} field (N 3.6): {@link #text} plus the problem
	 * sentence when there is one and the text does not already carry it (contract C29, K7).
	 */
	static String statusText(final BankPriceMovementPanel panel)
	{
		final Status status = panel.status;
		if (status == null)
		{
			return "";
		}
		final String text = text(status) == null ? "" : text(status);
		final String problem = status.problem();
		if (problem == null || problem.isEmpty() || text.contains(problem))
		{
			return text;
		}
		return text.isEmpty() ? problem : text + " - " + problem;
	}

	/** The line under the figures as drawn: the provenance footnote. */
	static String heroSubText(final BankPriceMovementPanel panel)
	{
		return panel.footnoteLabel().getText();
	}

	/** Whether the hero card is in the header - true exactly while a bank is loaded (M4, N section 3 §3). */
	static boolean heroShowing(final BankPriceMovementPanel panel)
	{
		return panel.hero().getParent() == panel.header();
	}

	/**
	 * Whether {@code c} is drawn on the hero card right now - which of the card's parts are IN it (O3), asked
	 * once instead of a named accessor per part restating the panel's {@code syncHero} rules.
	 *
	 * <p>The walk up the parent chain is what makes it one question: the total and the move line are children of
	 * the card, the gp figure and the percentage are children of the move LINE, and a switched-off part is
	 * removed rather than hidden - so a part is drawn exactly when every link above it is still in place.
	 * Whether the CARD itself is in the header is a different question ({@link #heroShowing}): the switches
	 * hold their answer while there is no bank to draw.
	 */
	static boolean shows(final BankPriceMovementPanel panel, @Nullable final Component c)
	{
		return c != null && SwingUtilities.isDescendingFrom(c, panel.hero());
	}

	/** Whether the "Show n more" row is in the list column. */
	static boolean showMoreVisible(final BankPriceMovementPanel panel)
	{
		return panel.showMoreRow.getParent() == panel.listColumn;
	}

	/**
	 * What the History view says about itself, for the bridge's {@code state.bankHistory} (amendment 9.11), and - 1.0.9
	 * part 5 - the record's {@code freshFrom}: the ISO date of the first day recorded by 1.0.9, {@code "all"} when every
	 * day in the file was recorded before it, and null (left out of the answer) when none was.
	 */
	static LinkedHashMap<String, Object> bankHistoryState(final BankPriceMovementPanel panel)
	{
		final LinkedHashMap<String, Object> map = describe(panel.historyView);
		final BankHistorySeries record = panel.status == null ? null : panel.status.bankHistory();
		final LocalDate fresh = record == null ? null : record.freshFrom();
		map.put("freshFrom", fresh == null ? null : LocalDate.MAX.equals(fresh) ? "all" : fresh.toString());
		// 1.1.1 part B: why the days before it are hidden (null = 1.0.9's reason) and the day the record was judged for
		// counted placeholders (null = not yet).
		map.put("freshWhy", record == null ? null : record.freshWhy());
		final LocalDate checked = record == null ? null : record.placeholdersChecked();
		map.put("placeholdersChecked", checked == null ? null : checked.toString());
		return map;
	}

	/**
	 * The dev bridge's view of the History card (amendment 9.11), exactly these seven keys and only String,
	 * Integer and null values: {@code readings} (the last show's {@code upTo(today).size()}), {@code first} and
	 * {@code last} (ISO {@code yyyy-MM-dd}, or null), {@code range} ({@link BankHistoryRange#label()}),
	 * {@code rows} and {@code carriedRows} (day rows built so far, carried ones among them) and {@code hoverDay}
	 * (the hovered point's day, ISO, or null).
	 */
	static LinkedHashMap<String, Object> describe(final BankHistoryView view)
	{
		final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		final BankHistoryPoint first = view.shown.first();
		final BankHistoryPoint last = view.shown.last();
		int carried = 0;
		for (BankHistoryDayRow row : view.rows)
		{
			if (row.model().carried)
			{
				carried++;
			}
		}
		final BankHistoryChart chart = view.chart;
		final BankHistoryMath.Day hovered = chart.hover >= 0 && chart.hover < chart.days.size()
			? chart.days.get(chart.hover) : null;
		map.put("readings", view.shown.size());
		map.put("first", first == null ? null : first.day().toString());
		map.put("last", last == null ? null : last.day().toString());
		map.put("range", view.range.label());
		map.put("rows", view.rows.size());
		map.put("carriedRows", carried);
		map.put("hoverDay", hovered == null ? null : hovered.day().toString());
		return map;
	}

	/**
	 * The three hero switches as an ordered map - {@code value}, {@code gp}, {@code pct} - which is the shape both
	 * JSON answers about them are built from: the dev bridge's {@code state.hero} and {@link #describe}'s echo of it
	 * (O3). A fresh {@link LinkedHashMap} per call, so a caller may hand it to Gson, add to it or keep it; the
	 * insertion order IS the field order those answers print in.
	 */
	static LinkedHashMap<String, Boolean> asMap(final HeroVisibility hero)
	{
		final LinkedHashMap<String, Boolean> m = new LinkedHashMap<>(4);
		m.put("value", hero.value());
		m.put("gp", hero.gp());
		m.put("pct", hero.pct());
		return m;
	}

	/**
	 * The view switches in the order the dev bridge prints them: {@code cash}, {@code untradeables}, {@code live},
	 * {@code inventory}, {@code ge}. Addendum AO line AO1 removed {@code holding} from between the second and the
	 * third with the switch it echoed; 1.0.9 part 3 put {@code ge} directly after {@code inventory}; release 1.2.0
	 * removed {@code hover}, the sixth, with the switch behind it.
	 */
	static LinkedHashMap<String, Boolean> asMap(final ViewOptions options)
	{
		final LinkedHashMap<String, Boolean> map = new LinkedHashMap<>();
		map.put("cash", options.countCash());
		map.put("untradeables", options.countUntradeables());
		map.put("live", options.livePrices());
		map.put("inventory", options.countInventory());
		map.put("ge", options.countGrandExchange());
		map.put("bonds", options.countBonds());
		return map;
	}

	/**
	 * The traded feeds' figures under T8's own key names, in T8's own order, with addendum U's two calendar keys after
	 * them: {@code fetchedAt}, {@code latestItems}, {@code liveRows}, {@code guideRows}, {@code alchRows}, then
	 * {@code liveDay} (an ISO date string, or null) and {@code windowDays} (an object keyed by window label -
	 * {@code 1d}, {@code 7d}, ... - of ISO date strings and nulls), and last contract 1.2.0's {@code hourAt} - the
	 * {@code /1h} bucket's start as an ISO-8601 UTC instant, or null.
	 *
	 * <p>Values are {@link Long}, {@link String}, a nested {@code LinkedHashMap<String, String>} and null, which is
	 * what lets this class write the object without a Gson. A writer that drops null values (Gson, unless
	 * {@code serializeNulls} is on) answers the same question a reader asks of it: a missing key and a null value
	 * both mean "that window has no traded bucket".
	 */
	static LinkedHashMap<String, Object> asMap(final Status.LiveStatus live)
	{
		final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		map.put("fetchedAt", live.fetchedAtMillis);
		map.put("latestItems", (long) live.latestItems);
		map.put("liveRows", (long) live.liveRows());
		map.put("guideRows", (long) live.guideRows);
		map.put("alchRows", (long) live.alchRows);
		map.put("liveDay", live.liveDay == null ? null : live.liveDay.toString());
		final LinkedHashMap<String, String> days = new LinkedHashMap<>();
		for (final MovementWindow window : MovementWindow.values())
		{
			final LocalDate day = live.windowDays().get(window);
			days.put(window.label(), day == null ? null : day.toString());
		}
		map.put("windowDays", days);
		map.put("hourAt", live.hourAtSeconds <= 0L ? null : Instant.ofEpochSecond(live.hourAtSeconds).toString());
		return map;
	}

	/** The card is SOFT when its solid rows hold less than this percentage of the counted value (L3). */
	static final int CARD_SOLID_VALUE_PCT = 90;

	/** ... or when its soft rows supply more than this percentage of its gp move (L3). */
	static final int CARD_SOFT_MOVE_PCT = 10;

	/**
	 * Whether the CARD is soft (L3): graded, and either the solid rows hold under {@value #CARD_SOLID_VALUE_PCT} % of
	 * the counted value or the soft rows supply over {@value #CARD_SOFT_MOVE_PCT} % of the gp move. Integer
	 * arithmetic, in doubles only when a product would overflow.
	 */
	static boolean soft(final GradeSummary g)
	{
		if (!g.graded())
		{
			return false;
		}
		final boolean solidShort = g.countedValue > 0L && under(g.solidValue, g.countedValue, CARD_SOLID_VALUE_PCT);
		final boolean softHeavy = g.totalMoveGp > 0L && over(g.softMoveGp, g.totalMoveGp, CARD_SOFT_MOVE_PCT);
		return solidShort || softHeavy;
	}

	/** {@code part x 100 < whole x pct}, in longs, and in doubles only when a product would overflow. */
	private static boolean under(final long part, final long whole, final int pct)
	{
		try
		{
			return Math.multiplyExact(part, 100L) < Math.multiplyExact(whole, (long) pct);
		}
		catch (final ArithmeticException overflow)
		{
			return part * 100.0d < whole * (double) pct;
		}
	}

	/** {@code part x 100 > whole x pct}, in longs, and in doubles only when a product would overflow. */
	private static boolean over(final long part, final long whole, final int pct)
	{
		try
		{
			return Math.multiplyExact(part, 100L) > Math.multiplyExact(whole, (long) pct);
		}
		catch (final ArithmeticException overflow)
		{
			return part * 100.0d > whole * (double) pct;
		}
	}

	/**
	 * Whether the row knows where its quantity is - true exactly when it was computed with "Include inventory and
	 * worn gear" or "Include Grand Exchange offers" on and something was merged. A row without the split prints no
	 * hover line about it (Y3), which is what a bank-only row did before addendum Y.
	 */
	static boolean split(final MovementRow row)
	{
		return row.bankQuantity() > 0 || row.inventoryQuantity() > 0 || row.wornQuantity() > 0
			|| row.exchangeQuantity() > 0;
	}

	/**
	 * The bridge's old line 2 - "1d   +12.4m   +1.0%", "1d   -   -" without a baseline (M4) - SYNTHESISED for
	 * {@code describe} so addendum M's live step 3 keeps reading it (N section 3 §8), whatever the switches
	 * hide on the card (O3: the bridge still echoes the portfolio in full). The percentage is truncated toward
	 * zero and signed by the gp figure, like a row's (L2).
	 */
	static String moveText(@Nullable final MovementWindow window, @Nullable final WindowMove move)
	{
		final String label = (window == null ? MovementWindow.DEFAULT : window).label();
		if (move == null)
		{
			return label + VALUE_SEP + MovementMath.DASH + VALUE_SEP + MovementMath.DASH;
		}
		return label + VALUE_SEP + MovementMath.formatDelta(move.deltaGp()) + VALUE_SEP
			+ MovementMath.formatPct(move.deltaPct(), move.deltaGp());
	}

	/** Whether {@code Widgets.markInvalid} last painted {@code f} red. */
	static boolean isMarkedInvalid(final FlatTextField f)
	{
		final Border b = f.getBorder();
		if (!(b instanceof CompoundBorder))
		{
			return false;
		}
		final Border outer = ((CompoundBorder) b).getOutsideBorder();
		return outer instanceof LineBorder && ColorScheme.PROGRESS_ERROR_COLOR.equals(((LineBorder) outer).getLineColor());
	}

	/** The fold's four chip texts in order - {@code describe}'s {@code presetLabels} (Z4). */
	static String[] presetLabels(final BankPriceMovementPanel panel)
	{
		final String[] out = new String[BankPriceMovementPanel.PRESET_COUNT];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = panel.presetLabel(i);
		}
		return out;
	}

	/**
	 * {@link #describe}'s {@code bank} object (addendum AS, section 7.3): the plugin's four values as the panel last
	 * mirrored them - {@code open}, {@code pending}, {@code heldEvents}, {@code reads} - with {@code glow}, whether the
	 * ring is actually lit, between them. It sits beside {@code showing} because the two are what decide whether
	 * a publish is built, and it is how a live run proves the plan's claims without a picture: one read per visit,
	 * events held while pending, the ring lit exactly while it should be.
	 */
	private static String bankJson(final BankPriceMovementPanel panel)
	{
		return "{\"open\":" + panel.bankOpen
			+ ",\"pending\":" + panel.bankPending
			+ ",\"glow\":" + panel.glowRunning()
			+ ",\"heldEvents\":" + panel.bankHeldEvents
			+ ",\"reads\":" + panel.bankReads
			+ "}";
	}

	/**
	 * What the traded feeds delivered for the publish on screen (addendum T, line T8), for {@link #describe}:
	 * the last status's {@link Status.LiveStatus}, and {@link Status.LiveStatus#OFF} - every figure 0, every day
	 * null - before the first publish, so {@code state.live} is an object with the same keys in every state a
	 * script can find the panel in.
	 */
	private static Status.LiveStatus liveStatus(final BankPriceMovementPanel panel)
	{
		final Status.LiveStatus live = panel.status == null ? null : panel.status.live();
		return live == null ? Status.LiveStatus.OFF : live;
	}

	/** The change settings as a failure message names them: {@code ChangeText{gp=NORMAL, pct=NORMAL, brightness=55}}. */
	static String text(final ChangeText text)
	{
		return "ChangeText{gp=" + text.gp().name() + ", pct=" + text.pct().name() + ", brightness=" + text.brightness() + "}";
	}

	/** One tab's change settings as the dev bridge echoes them: {@code {gpSize, pctSize, gpOpacity}}. */
	private static String textJson(final ChangeText text)
	{
		return "{\"gpSize\":\"" + text.gp().name() + "\",\"pctSize\":\"" + text.pct().name() + "\",\"gpOpacity\":"
			+ text.brightness() + "}";
	}

	/**
	 * A JSON object of booleans, in the map's own order - the three hero switches, or the view switches. Written by
	 * hand rather than with Gson, as the panel it describes is Swing and has no injected Gson.
	 */
	private static String flags(final Map<String, Boolean> values)
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
	 * {@link #flags} for {@link #asMap(Status.LiveStatus)} - the five figures of T8 and addendum U's two calendar
	 * keys, which together are what {@code state.live} is.
	 *
	 * <p>Four value shapes, which is every shape that map holds: a {@link Number} prints as a number, a
	 * {@link String} (an ISO date) as a quoted string, a nested {@link Map} as a nested object - one level, which
	 * is all {@code windowDays} is - and a null as JSON {@code null}, because "that window has no traded bucket" is
	 * a fact and not a zero. Anything else would be a new key nobody has taught this to write, so it is refused
	 * rather than printed as its {@code toString}.
	 */
	private static String values(final Map<?, ?> map)
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
	private static String numbers(final long[] values)
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
	private static String texts(final String[] values)
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
	private static String value(@Nullable final Object v)
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
		throw new IllegalArgumentException("no JSON shape for " + v);
	}

	private static String json(@Nullable final String s)
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
}
