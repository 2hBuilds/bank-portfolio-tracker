package com.bankpricemovement;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * The plugin's single config (contract C39; items 7-9 are addendum O line O2, items 10-11 addendum Q line Q3,
 * item 12 addendum Y line Y1, item 13 addendum AH and item 14 addendum T line T1). Fifteen items, and every one
 * of them is also a widget in the sidebar: the config panel and the sidebar are the same switch, so a change in
 * either place is written here and read back by the other through {@code ConfigChanged}.
 *
 * <p><b>Addendum AO took one away</b> (AO1), the first key this plugin has ever dropped: {@code holdingOnRows}
 * ("Show stack value on rows", addendum Q line Q3), which chose whether a row printed the per-ITEM reading or the
 * per-STACK one. Addendum AN's three-line row prints BOTH - line 2 is the stack, line 3 is one item - so the
 * switch stopped reaching anything drawn, and the user, asked whether to keep it, answered "if it doesnt do
 * anything anymore then remove it". The one thing it still did was pick {@link SortMode#GP_MOVE}'s comparison
 * key, which is now the STACK's gp move permanently - the reading the switch gave when it was ON, because this is
 * a portfolio tracker and "biggest gainers" over a bank means the holding that gained the most gp. That is a
 * user-visible change to one column's default ordering, and it is recorded as one. A stored {@code holdingOnRows}
 * is swept once at startUp the way addendum N's deleted {@code look} is
 * ({@link BankPriceMovementPlugin#unstickLook()}), so no orphan sits in a profile forever. Sixteen items became
 * fifteen and the two below it moved up by one.
 *
 * <p><b>Addendum AA added a fifteenth</b> (AA1): whether the price fold under the control row - the chip strip
 * over the Min / Max fields - is open. It is a remembered piece of the sidebar's SHAPE rather than a figure or a
 * band, so it takes a road of its own ({@link BankPriceMovementPlugin#isFoldKey}) that reaches the panel and
 * nobody else. It sits directly after {@code bandPresets}, the item it opens the reader's view onto, and the
 * positions below it moved down by one again.
 *
 * <p><b>Addendum Z added the fourteenth</b> (Z1), the one item that is neither a switch nor a number: the three
 * gp bands the fold's chips offer, as one line of shorthand. It sits directly after {@code gpMax} because it is
 * about the same thing those two are - the price band - and the positions below it moved down by one, which they
 * may: positions are not frozen, keys are.
 *
 * <p><b>Addendum Y renamed six of them</b> (Y4) and added the thirteenth (Y1). The names are the gear menu's
 * labels - the menu item IS the config item's {@code name} - so both were re-read in plain words at once:
 * "Show change in gp" and "Show change in %" for the card's two move figures, "Use live prices", "Include coins
 * and platinum tokens", "Include untradeable items", "Include inventory and worn gear" and "Show stack value on
 * rows". Every stored KEY is untouched by that, and must be: a rename discards the setting. Positions are not
 * frozen and were renumbered to put the new item where the menu lists it.
 *
 * <p><b>Why this interface extends {@link Config} and nothing else.</b> RuneLite's
 * {@code ConfigManager.setDefaultConfiguration} only walks {@code getDeclaredMethods()} of the interface it
 * is given (1.12.37, ConfigManager.java), while {@code ConfigManager.getConfigDescriptor} builds the panel
 * from {@code getMethods()}. An item inherited from a super-interface is therefore rendered but never gets a
 * stored default, and {@code ConfigPanel.createComboBox} then throws an uncaught NPE out of
 * {@code Enum.valueOf(type, null)} - so the whole config panel fails to open. {@code BankPriceMovementConfigTest}
 * guards the flat shape.
 *
 * <p><b>Enums store their {@code name()}.</b> {@code ConfigManager.objectToString} writes
 * {@code ((Enum) object).name()} (ConfigManager.java:1281) and {@code stringToObject} reads it back with
 * {@code Enum.valueOf} (ConfigManager.java:1204-1207), so the stored value is "D1" / "PERCENT_MOVE" whatever
 * {@link MovementWindow#toString()} and {@link SortMode#toString()} answer - those return the LABEL ("1d",
 * "Percent change"), which is what the config panel's combo box shows the user.
 *
 * <p><b>Addendum K renamed the window constants</b> (H1 / H24 / D7 became D1 / D7 / D30 / D90 / D180, because
 * movement is now measured on the daily GUIDE price and an hourly window would read 0 % for every row). A
 * profile written by the first build therefore still holds {@code window=H24}, a string that no longer names a
 * constant; {@link BankPriceMovementPlugin#unstickWindow()} sweeps it at startUp (K9, and the citation there
 * for what RuneLite does with a stored value it cannot unmarshal).
 *
 * <p>Group and key names are frozen from the first release: renaming one discards every user's setting.
 */
@ConfigGroup(BankPriceMovementConfig.GROUP)
public interface BankPriceMovementConfig extends Config
{
	/** The config group, shared with {@link BankPriceMovementPlugin}'s {@code ConfigChanged} gate. */
	String GROUP = "bankpricemovement";

	@ConfigItem(
		position = 0,
		keyName = "gpMin",
		name = "Min unit price (gp)",
		description = "Hide items whose unit price is below this. 0 = no lower bound."
	)
	default int gpMin()
	{
		return 0;
	}

	@ConfigItem(
		position = 1,
		keyName = "gpMax",
		name = "Max unit price (gp)",
		description = "Hide items whose unit price is above this. 0 = no upper bound."
	)
	default int gpMax()
	{
		return 0;
	}

	/**
	 * Addendum Z line Z1, the fourteenth key and the only one that is not a switch, a number or a choice from a
	 * list: the three quick bands the fold draws under the band button, written the way a player writes a price.
	 * The user asked for them because the three this plugin shipped with are one bank's worth of bands and not
	 * everybody's - "People with large banks might want (1m+, 10m+, 100m+) or other variants" (2026-09-13) - and
	 * the answer is a line here plus three boxes at the foot of the gear menu, which are the same setting - and
	 * which say so by carrying the same words, "Preset price ranges"
	 * ({@link BankPriceMovementPanel#PRESETS_TEXT}, which line Z7 and then addendum AB line AB3 rewrote at the
	 * user's word so that both places name what the boxes DO, in three words;
	 * {@code docs/bank-price-movement-addendum-AB-2026-09-13.md}. The KEY is untouched by either rename, so a
	 * stored trio survives them).
	 *
	 * <p><b>Why a String and not three ints.</b> Three keys would be three settings-page rows, three
	 * {@code ConfigChanged} events per change and an ORDER a user could get wrong in three ways; one line is what
	 * the boxes hold between them, in the shorthand the Min / Max fields already accept
	 * ({@link MovementMath#parseGp(String)}), and it round-trips: {@code BandPresets.format()} writes exactly what
	 * {@code BandPresets.parse} reads back. The value carries the rule as well as the numbers - three DISTINCT
	 * POSITIVE amounts, held smallest first - so a line typed here by hand is sorted rather than obeyed, and a line
	 * that breaks the rule is refused with the stored presets left standing ({@code Prefs.loadPresets} reads
	 * anything unparseable as {@link BandPresets#DEFAULT}, so a hand-edited profile can never leave the fold
	 * without chips).
	 *
	 * <p>It is a PANEL matter and takes a road of its own to prove it ({@link BankPriceMovementPlugin#isPresetKey}):
	 * the service is never told, because a preset decides what the CHIPS offer and not what the list contains -
	 * the band a chip applies is {@code gpMin} / {@code gpMax}, which are two keys above this one and untouched by
	 * a preset change. So editing these never moves a row.
	 */
	@ConfigItem(
		position = 2,
		keyName = "bandPresets",
		name = "Preset price ranges",
		description = "The three quick bands under the band button, in gp shorthand and smallest first -"
			+ " for example 1m, 10m, 100m."
	)
	default String bandPresets()
	{
		// The literal rather than BandPresets.DEFAULT.format(), for the reason every default on this interface is
		// a constant: ConfigManager stores what this body answers, and a stored default that could change with a
		// formatter is a stored default that could stop matching what the boxes print.
		// BankPriceMovementConfigTest pins the two together.
		return "100k, 1m, 10m";
	}

	/**
	 * Addendum AA line AA1, the fifteenth key: whether the price fold - the chip strip the item above fills, over
	 * the Min / Max fields - is drawn under the control row. The user asked for it on the addendum Z build, with a
	 * picture of the open fold: "can you by default have 'All items' expanded too so the user can see the quick
	 * price presets on the main tab? ... currently it starts minimized" (2026-09-13).
	 *
	 * <p><b>So the default is true</b>, which is the whole of the request: the three quick bands were made editable
	 * one wave ago and a control nothing shows is a control nobody edits. A reader who prefers the short header
	 * closes the fold once with the band button and the choice is remembered - which is the other half, and the
	 * reason this is a stored key rather than a constant.
	 *
	 * <p><b>Its name follows the item above it</b> (addendum AB line AB3,
	 * {@code docs/bank-price-movement-addendum-AB-2026-09-13.md}): the user renamed those three boxes "Preset price
	 * ranges", so what SHOWS them is "Show preset price ranges" and the description names them the same way. Two
	 * settings-page rows that uncover one another should not be two different nouns; the key is untouched.
	 *
	 * <p>It sits directly after {@code bandPresets} because it decides whether that item is visible at all, and it
	 * takes a road of its own ({@link BankPriceMovementPlugin#isFoldKey}) that reaches the panel and stops there.
	 * Not the filter's: the fold is where the band is TYPED, never a bound itself, so opening or closing it moves
	 * no row - {@code gpMin} and {@code gpMax} keep whatever the user last applied, and a closed fold still filters.
	 * Not the option road either: no figure is recomputed, so the service is never told.
	 */
	@ConfigItem(
		position = 3,
		keyName = "foldOpen",
		name = "Show preset price ranges",
		description = "Keep the preset price ranges and the Min / Max fields open under the sort button."
			+ " Clicking the band button folds them away or back."
	)
	default boolean foldOpen()
	{
		return true;
	}

	/**
	 * The column half of the sidebar's ordering, and since addendum W the dropdown here lists exactly the four
	 * columns the sidebar's own sort menu lists, in the same order and the same words (W1): "Percent change",
	 * "gp change", "Item price", "Stack price". {@link SortMode#toString()} is the label, so the settings page
	 * needs no vocabulary of its own.
	 *
	 * <p>These two items are a PAIR, and addendum W is what made the pair readable: the sort button now draws
	 * the lit column with an ARROW for the direction, so "Sort column" plus "Biggest first" is literally what a
	 * user sees over there - where addendum N had to invent six sentences ("Biggest gainers", "Cheapest") to
	 * give the same pair a name, and this description had to teach them. What a settings page still cannot show
	 * is the gesture, so the description says it: pressing the lit column again flips the direction.
	 *
	 * <p>The two stored KEYS are frozen (a rename discards every user's setting) and so is every constant name
	 * behind this one - adding a column is allowed, renaming one is not.
	 */
	@ConfigItem(
		position = 4,
		keyName = "sortMode",
		name = "Sort column",
		description = "Which column the list is ordered on: Percent change, gp change, Item price or Stack price -"
			+ " pressing the lit column again in the sidebar flips the direction."
	)
	default SortMode sortMode()
	{
		return SortMode.PERCENT_MOVE;
	}

	/**
	 * The direction half of the pair, and since addendum W it is a thing a user can SEE: it is the arrow the
	 * sidebar's sort button draws beside the lit column (W2), down for biggest first and up for smallest. The
	 * description names both ends, because half the ordering is invisible from this page otherwise.
	 */
	@ConfigItem(
		position = 5,
		keyName = "sortDescending",
		name = "Biggest first",
		description = "On is biggest first and the sidebar's arrow points down; off is smallest first and it"
			+ " points up. Items with nothing to sort on always come last."
	)
	default boolean sortDescending()
	{
		return true;
	}

	@ConfigItem(
		position = 6,
		keyName = "window",
		name = "Movement window",
		description = "How far back the guide-price change is measured: 1d, 7d, 30d, 90d or 180d."
	)
	default MovementWindow window()
	{
		return MovementWindow.DEFAULT;
	}

	/**
	 * Addendum O line O2, the first of three: whether the hero card prints the whole-bank total. The user asked
	 * for the card's three figures to be independently hideable - "have an option to show/hide the bank value
	 * and pnl and percentage" (2026-09-09) - and these three items are that option, carried between the config
	 * panel, the card's right-click menu and the panel by {@link HeroVisibility}.
	 *
	 * <p>Presentation only, so like addendum N's deleted {@code look} item it is NOT part of {@link RowFilter}:
	 * the panel's {@code applyHeroVisibility} re-renders the card and nothing else moves, and no figure this
	 * hides stops being computed - {@code state.portfolio} still echoes the whole bank on the dev bridge.
	 */
	@ConfigItem(
		position = 7,
		keyName = "showBankValue",
		name = "Show bank value",
		description = "Show the whole-bank total on the card"
	)
	default boolean showBankValue()
	{
		return true;
	}

	/** O2, the second: the gp half of the card's move line ("+12.4m"). Renamed by Y4. */
	@ConfigItem(
		position = 8,
		keyName = "showBankMoveGp",
		name = "Show change in gp",
		description = "Show the bank's gp change for the chosen window"
	)
	default boolean showBankMoveGp()
	{
		return true;
	}

	/**
	 * O2, the third: the percentage half of the card's move line ("+1.0%"). With the gp half off as well the
	 * move line is removed altogether, and the direction triangle with it (O3). Renamed by Y4.
	 */
	@ConfigItem(
		position = 9,
		keyName = "showBankMovePct",
		name = "Show change in %",
		description = "Show the bank's percentage change for the chosen window"
	)
	default boolean showBankMovePct()
	{
		return true;
	}

	/**
	 * Addendum Q line Q3, the first of the two it still has - the third, {@code holdingOnRows}, is gone with
	 * addendum AO line AO1: whether coins and the 1,000 gp a platinum token is worth count in
	 * the bank value. They always have (addendum P), and the user asked for the choice - "i would like options
	 * for 1) include coins and platinum tokens in total" (2026-09-11) - so the item defaults to ON and is a way
	 * to take the cash back OUT of the headline for a reader who thinks of their bank as the stacks alone.
	 *
	 * <p>Nothing is lost by turning it off: {@code BankSnapshot.currencyGp} still records what was counted, so
	 * the switch needs no bank visit in either direction (Q4). It is not a {@link RowFilter} field - cash is
	 * never a ROW - but unlike addendum O's three it is not presentation either: {@code PriceService} passes 0
	 * into {@code PortfolioMath} while it is off, so the bank value and every window's two sides are recomputed.
	 * It travels with the gear menu's other switches as one {@link ViewOptions}.
	 */
	@ConfigItem(
		position = 10,
		keyName = "countCash",
		name = "Include coins and platinum tokens",
		description = "Coins and platinum tokens (1,000 gp each) count in the bank value"
	)
	default boolean countCash()
	{
		return true;
	}

	/**
	 * Q3, the second: whether stacks the Grand Exchange will not trade are listed at their High Alchemy value
	 * ("i would like options for ... 2) include untradables"). Default OFF, because the list this plugin is for
	 * is the tradeable one and an untradeable stack has no guide price and therefore no movement at all: it
	 * arrives as a row with an "alch" tag where the move figures go, counted in the bank value and in the
	 * "N of M stacks" line, and in no window's sums (Q5).
	 */
	@ConfigItem(
		position = 11,
		keyName = "countUntradeables",
		name = "Include untradeable items",
		description = "List untradeable stacks at their tradeable parts' value, or else their High Alchemy value,"
			+ " and count them in the bank value"
	)
	default boolean countUntradeables()
	{
		return false;
	}

	/**
	 * Addendum Y line Y1, the thirteenth item and the fifth field of {@link ViewOptions}: whether what the player
	 * is CARRYING - the inventory and the worn gear - is counted and listed beside the bank's own stacks. The user
	 * asked for it in those words - "i would also like to add an option that is on by default (Include inventory
	 * and gear equipped) that makes sure to include all items in our inventory and worn items gear equipped tab"
	 * (2026-09-13) - and it defaults ON, because a "bank value" that ignores the 100m of gear a player is standing
	 * in is not the figure they mean.
	 *
	 * <p><b>The description says WHEN, because the answer is not "always"</b> (Y2). The two containers are read at
	 * exactly two moments, both of them already client-thread work this plugin does: the bank container event it
	 * has always handled, and the Refresh link. An inventory change between the two is not tracked - deliberately,
	 * the user's own choice - so a row's quantity follows the next bank visit or the next Refresh and nothing else.
	 *
	 * <p><b>Addendum AS moved a visit's read to the CLOSE, so the sentence names the close.</b> With the bank open, a
	 * bank event that finds the bank, the inventory or the worn gear changed since the last read is held and read
	 * once - when the bank closes (a logout or a hop counts as a close), or when the Refresh link is clicked with it
	 * still open - so "when you open the bank" had become the moment a change is noticed, not the one it is read.
	 * The one sentence leaves out reads that come EARLIER than it promises: a changed bank event is still read at
	 * once when there is no earlier read of that account and profile since the plugin started to compare it with
	 * (the first bank of a client run, or another account's first), or when the bank is not known to be open. And
	 * it leaves out one gap: a close with nothing held reads nothing, so a gear swap made inside a visit that changed
	 * nothing else waits for Refresh or the next visit - Y2's gap between two moments, not a new one.
	 *
	 * <p>An item held in two places is ONE row with the quantities summed, and its tooltip names the split
	 * ("3 in bank, 1 in inventory, 1 worn"); the card's hover says the total includes them (Y3). Off, every figure
	 * is the bank alone - byte for byte the addendum X sidebar.
	 */
	@ConfigItem(
		position = 12,
		keyName = "countInventory",
		name = "Include inventory and worn gear",
		description = "Items in your inventory and worn gear count in the bank value and are listed with the bank's"
			+ " stacks. They are read when you close the bank or press Refresh."
	)
	default boolean countInventory()
	{
		return true;
	}

	/**
	 * Addendum AH, narrowed by AI and AJ: whether the sidebar shows hover text at all. Default OFF, and the only
	 * switch on this page whose default is the quieter sidebar.
	 *
	 * <p>It reaches the bank value's hover and every CONTROL's - the sort button, the chips, Refresh, the gear's
	 * own items, the "Item prices update every 24hrs" line. It does not reach the item rows, because since
	 * addendum AI a row carries no tooltip at any setting: its description is the block the cell opens when it
	 * is clicked, so there is nothing there for a switch to silence.
	 */
	@ConfigItem(
		position = 13,
		keyName = "showHoverText",
		name = "Show hover text",
		description = "Show hover text anywhere in the sidebar: the bank value and the controls"
	)
	default boolean showHoverText()
	{
		return false;
	}

	/**
	 * Addendum T line T1, the last item on the settings page: whether an item the Grand Exchange is actually TRADING is
	 * priced from the wiki's live traded series rather than from Jagex's once-a-day guide table. The user asked
	 * for it in those words - "for the current 24 hour window if its accurate we should use live prices so people
	 * can refresh and see live changes" (2026-09-12) - and the answer is a switch rather than a rewrite because a
	 * live price is only better where there is trade behind it: on this bank today the live 1d figure and the guide
	 * 1d figure differ by a median 4.3 points a row, and 87 rows read over 20 % under live against 1 under guide,
	 * almost all of them junk nobody buys ({@code docs/research/live-1d-study-2026-09-12.md}).
	 *
	 * <p>So the switch is not "live prices" wholesale: with it ON a stack is priced live only when it passes three
	 * liquidity checks (T3 - at least 100 units traded yesterday, a buy/sell gap inside 10 %, and a live price
	 * within 50 % of the guide), and everything that fails one of them keeps the daily guide price it has always
	 * had. With it OFF not one traded request is made and every figure is exactly what addenda K to S produced -
	 * which is also the only setting in which the plugin's figures match the Grand Exchange website to the gp.
	 *
	 * <p>Default ON (the user's choice). It travels with the gear menu's other four as one {@link ViewOptions},
	 * takes the same {@code ConfigChanged} road as they do ({@link BankPriceMovementPlugin#isOptionKey(String)}),
	 * and is the first check item in the gear's last group.
	 */
	@ConfigItem(
		position = 14,
		keyName = "livePrices",
		name = "Use live prices",
		description = "Actively traded items use the wiki's live traded prices for every figure;"
			+ " thin items keep the daily guide price"
	)
	default boolean livePrices()
	{
		return true;
	}
}
