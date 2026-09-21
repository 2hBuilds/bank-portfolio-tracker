package com.bankpricemovement;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the flat shape of {@link BankPriceMovementConfig} (contract C39). RuneLite's
 * {@code ConfigManager.setDefaultConfiguration} only walks {@code getDeclaredMethods()} of the config
 * interface, while {@code ConfigManager.getConfigDescriptor} builds the panel from {@code getMethods()}.
 * An item inherited from a super-interface is therefore rendered but never gets a stored default, and
 * {@code ConfigPanel.createComboBox} then throws an uncaught NPE out of {@code Enum.valueOf(type, null)},
 * so the config panel cannot open at all. Cloned from {@code LootAndBeamConfigTest}, which guards the same
 * thing for the other plugin.
 */
public class BankPriceMovementConfigTest
{
	private static final List<String> EXPECTED_KEYS = Arrays.asList(
		"gpMin",
		"gpMax",
		// Addendum Z line Z1: it landed as the fourteenth key and is the thirteenth since addendum AO, and it is
		// the only free-text one - the three quick bands the fold's chips offer, as one line of gp shorthand. It
		// sits beside the two bounds because it is about the same thing they are.
		"bandPresets",
		// Addendum AA line AA1: it landed as the fifteenth key and is the fourteenth since addendum AO - whether
		// the fold those chips live in is open. Directly after the presets, because it decides whether they are
		// on screen at all.
		"foldOpen",
		"sortMode",
		"sortDescending",
		"window",
		// Addendum O line O2: the bank value card's three show/hide switches. Addendum N's "look" was deleted
		// with the One Bar design (O1) - BankPriceMovementPlugin.unstickLook() sweeps it off old profiles.
		"showBankValue",
		"showBankMoveGp",
		"showBankMovePct",
		// Addendum Q line Q3: the gear menu's view switches, carried as one ViewOptions. It brought THREE and has
		// two - addendum AO line AO1 deleted "holdingOnRows", the second key to go for good after addendum N's
		// "look", and theHoldingSwitchIsGone below is the guard that keeps it gone.
		"countCash",
		"countUntradeables",
		// Addendum T line T1: the live-traded-price switch, the third field of the same ViewOptions since AO
		// (it landed as the fourth).
		"livePrices",
		// Addendum Y line Y1: the carried switch, the fourth field of the same ViewOptions since AO (it landed as
		// the fifth) and the thirteenth key added to the group. On by default, unlike the two switches beside it.
		"countInventory",
		// Addendum AH: it landed as the sixteenth key and the sixth field of the same ViewOptions, and is the
		// fifteenth and the fifth since addendum AO - whether the sidebar shows hover text at all. OFF by default,
		// alone on this page in showing LESS than the build before it. It was deleted for one day by addendum AI
		// and restored by AJ, which is why a stored value under this spelling may have been swept once: the key
		// never changed, so a profile that kept it still reads back.
		"showHoverText"
	);

	/** Every zero-argument {@code @ConfigItem} method visible on the interface, inherited ones included. */
	private static List<Method> itemMethods()
	{
		final List<Method> methods = new ArrayList<>();
		for (Method m : BankPriceMovementConfig.class.getMethods())
		{
			if (m.getParameterCount() == 0 && m.isAnnotationPresent(ConfigItem.class))
			{
				methods.add(m);
			}
		}
		return methods;
	}

	@Test
	public void extendsConfigDirectlyAndNothingElse()
	{
		assertEquals(
			"BankPriceMovementConfig must extend net.runelite.client.config.Config and no other interface",
			Arrays.asList(Config.class),
			Arrays.asList(BankPriceMovementConfig.class.getInterfaces()));
	}

	@Test
	public void everyItemIsDeclaredOnThisInterface()
	{
		for (Method m : itemMethods())
		{
			assertEquals(
				"inherited config items get no defaults: ConfigManager.setDefaultConfiguration uses getDeclaredMethods()",
				BankPriceMovementConfig.class,
				m.getDeclaringClass());
		}
	}

	/**
	 * The whole stored surface of the plugin in one assertion, and the place the Hub's "never rename a config key
	 * without a migration" rule is guarded: {@link #EXPECTED_KEYS} is the frozen list, so a rename shows up here as
	 * two failures at once (the old spelling missing, the new one unexpected) rather than as a silently discarded
	 * setting on every user's profile. A key ADDED must be added to the list deliberately - which is the whole of
	 * the ceremony, and the reason it is cheap enough to keep.
	 *
	 * <p>Fifteen since addendum AO, which took one away and put nothing in its place: {@code holdingOnRows} went
	 * (AO1) because addendum AN's three-line row prints the stack and the item both, so the switch reached
	 * nothing drawn. It went sixteen at addendum AH ({@code showHoverText}), fifteen when addendum AI deleted that
	 * one, sixteen again when AJ restored it under the same spelling - a key that goes and comes back with its name
	 * and its type intact reads its old stored value straight off the profile, so no migration was owed there - and
	 * fifteen at AO. The deleted key is swept off old profiles rather than migrated
	 * ({@link BankPriceMovementPlugin#unstickHolding()}), because nothing survives it to migrate INTO.
	 */
	@Test
	public void declaresExactlyTheExpectedItems()
	{
		final List<Method> methods = itemMethods();
		final Set<String> keys = new TreeSet<>();
		for (Method m : methods)
		{
			keys.add(m.getAnnotation(ConfigItem.class).keyName());
		}
		assertEquals("unexpected set of config keys", new TreeSet<>(EXPECTED_KEYS), keys);
		assertEquals("unexpected number of config items", 15, methods.size());
	}

	/**
	 * Two keys may never share a spelling (a rename is a discarded setting) and two items may never share a
	 * position, because RuneLite sorts the page on it and a tie is drawn in whatever order reflection answers in.
	 *
	 * <p>The positions also have to run 0, 1, 2 ... with no gap, which is worth pinning since addendum AO line
	 * AO1: it is the first change that ever DELETED an item from the middle of the page, and the cheap version
	 * of that change - leave the hole where {@code holdingOnRows} was - is exactly the one nobody would notice by
	 * reading the interface. Positions are not frozen the way keys are, so renumbering is allowed and leaving a
	 * hole is the thing being ruled out.
	 */
	@Test
	public void keyNamesAndPositionsAreUniqueAndRunFromZeroWithNoGap()
	{
		final Set<String> keys = new HashSet<>();
		final Set<Integer> positions = new HashSet<>();
		for (Method m : itemMethods())
		{
			final ConfigItem item = m.getAnnotation(ConfigItem.class);
			assertTrue("duplicate keyName: " + item.keyName(), keys.add(item.keyName()));
			assertTrue("duplicate position " + item.position() + " on " + item.keyName(),
				positions.add(item.position()));
		}
		for (int position = 0; position < positions.size(); position++)
		{
			assertTrue("no config item sits at position " + position + ", so the settings page has a gap in it",
				positions.contains(position));
		}
	}

	@Test
	public void everyItemHasADefaultBody()
	{
		for (Method m : itemMethods())
		{
			assertTrue(
				m.getName() + " has no default body, so ConfigManager stores no default for it",
				m.isDefault());
		}
	}

	@Test
	public void configGroupMatchesTheConstant()
	{
		final ConfigGroup group = BankPriceMovementConfig.class.getAnnotation(ConfigGroup.class);
		assertNotNull("BankPriceMovementConfig is missing @ConfigGroup", group);
		assertEquals(BankPriceMovementConfig.GROUP, group.value());
		// The group is written into every stored key and into the ConfigChanged gate: freezing it here means a
		// rename cannot slip through and silently discard every user's settings.
		assertEquals("bankpricemovement", BankPriceMovementConfig.GROUP);
	}

	/**
	 * The five FILTER defaults are {@link RowFilter#DEFAULT}: the sidebar with nothing stored yet and the
	 * config panel with nothing stored yet have to agree, or the first {@code ConfigChanged} would move the
	 * list. The last three items are not part of the filter at all (O2) and are checked below.
	 */
	@Test
	public void theDefaultsAreTheDefaultFilter()
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertEquals(0, config.gpMin());
		assertEquals(0, config.gpMax());
		assertEquals(SortMode.PERCENT_MOVE, config.sortMode());
		assertTrue("biggest first is the default direction", config.sortDescending());
		// Addendum K4: one DAY, not the old 24h trade bucket. The guide table is republished about once a day,
		// so anything shorter would put both ends of the window on the same revision and read 0 % everywhere.
		assertEquals(MovementWindow.DEFAULT, config.window());
		assertEquals(MovementWindow.D1, config.window());

		assertEquals(RowFilter.DEFAULT, new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(),
			config.sortDescending(), config.window()));
	}

	/**
	 * The stored form of the window is the constant's {@code name()}, and that is what the K9 guard in
	 * {@link BankPriceMovementPlugin#unstickWindow()} matches against: "D1", never the "1d" the chip shows.
	 * {@code ConfigManager.objectToString} writes {@code ((Enum) object).name()} (ConfigManager.java:1281)
	 * and {@code stringToObject} reads it back with {@code Enum.valueOf} (ConfigManager.java:1204-1207), so a
	 * {@code toString()} that answers the label - which {@link MovementWindow} deliberately does, for the
	 * config panel's combo box - must not be mistaken for the stored spelling.
	 */
	@Test
	public void theWindowIsStoredUnderItsConstantNameNotItsLabel()
	{
		for (MovementWindow w : MovementWindow.values())
		{
			assertEquals(w, Enum.valueOf(MovementWindow.class, w.name()));
			assertEquals("the combo box shows the label", w.label(), w.toString());
		}
		assertEquals("D1", MovementWindow.DEFAULT.name());
	}

	/**
	 * Addendum O line O2: every one of the card's three figures is SHOWN on a fresh profile - the switches are
	 * a way to take something away, so a user who never opens them sees the card addendum M built - and none of
	 * the three is a filter field, so the default filter is unchanged by their arrival (they are a re-render,
	 * never a refetch).
	 */
	@Test
	public void theThreeCardSwitchesDefaultToShownAndAreNotPartOfTheFilter()
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertTrue("the bank total is shown by default", config.showBankValue());
		assertTrue("so is its gp move", config.showBankMoveGp());
		assertTrue("and so is its percentage", config.showBankMovePct());
		assertEquals(HeroVisibility.ALL, HeroVisibility.of(config.showBankValue(), config.showBankMoveGp(),
			config.showBankMovePct()));
		assertEquals(RowFilter.DEFAULT, new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(),
			config.sortDescending(), config.window()));
	}

	/**
	 * Addendum Q line Q3: the gear menu's switches open on {@link ViewOptions#DEFAULT} - cash counted (it always
	 * was, addendum P) and untradeables left out - so a user who never opens the gear sees exactly the sidebar
	 * addendum P shipped. Neither is a {@link RowFilter} field: they change what the figures MEAN rather than
	 * which rows the band and the ordering select.
	 *
	 * <p>Q3 brought a THIRD, {@code holdingOnRows}, which changed only what a row printed - and that is exactly
	 * why addendum AO line AO1 could delete it once addendum AN's row printed both readings at once. The Q3
	 * default it used to assert here ("a row prints the unit price until the user asks for the holding") is not
	 * a thing a fresh profile can be asked any more; {@link #theHoldingSwitchIsGone()} guards the absence
	 * instead.
	 */
	@Test
	public void theTwoSurvivingViewSwitchesDefaultToTheDefaultViewOptions()
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertTrue("coins and platinum count in the bank value on a fresh profile", config.countCash());
		assertFalse("untradeables are left out until the user asks for them", config.countUntradeables());
		assertEquals(ViewOptions.DEFAULT, optionsOf(config));
		assertEquals(RowFilter.DEFAULT, new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(),
			config.sortDescending(), config.window()));
	}

	/**
	 * Q2/Q3, in the words addendum Y line Y4 gave them: the gear menu's check items and RuneLite's settings
	 * page are the same switch, so they are read in the same words - the menu item IS the config item's
	 * {@code name}, and the description is the sentence that explains it. The words are pinned here because
	 * they are the whole of what a user has to go on, and because the menu builds its labels from these
	 * phrases. Y4 rewrote all three of them - the cash switch, the untradeables switch and the row switch each
	 * lost a verb only this plugin used ("Count ...", "Show holding value ...") for a word a player would reach
	 * for - and left every stored KEY where it was, which is the half that must never move: a renamed key
	 * discards the setting.
	 *
	 * <p>Two of the three are left. "Show stack value on rows" ({@code holdingOnRows}) was deleted whole by
	 * addendum AO line AO1, so the words Y4 chose for it are history and are not pinned anywhere any more; the
	 * positions of these two did not move with it, because it sat BELOW them.
	 */
	@Test
	public void theViewSwitchesAreNamedAsTheGearMenuNamesThem()
	{
		final ConfigItem cash = item("countCash");
		assertEquals("Include coins and platinum tokens", cash.name());
		assertEquals("Coins and platinum tokens (1,000 gp each) count in the bank value", cash.description());
		assertEquals(10, cash.position());

		final ConfigItem untradeables = item("countUntradeables");
		assertEquals("Include untradeable items", untradeables.name());
		assertEquals("List untradeable stacks at their tradeable parts' value, or else their High Alchemy value,"
			+ " and count them in the bank value", untradeables.description());
		assertEquals(11, untradeables.position());
	}

	/**
	 * Y1: the key it added is ON for a fresh profile - a "bank value" that ignored the gear the player is
	 * standing in is not the figure they mean - and it is the FOURTH field of {@link ViewOptions} since addendum
	 * AO line AO1 took {@code holdingOnRows} out from above it (it landed as the fifth). Never a
	 * {@link RowFilter} field: it changes what a stack's quantity IS and which stacks are rows at all, not
	 * which of them the band and the ordering select.
	 */
	@Test
	public void theCarriedSwitchDefaultsToOnAndIsTheFourthViewOption()
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertTrue("the inventory and the worn gear count on a fresh profile (Y1)", config.countInventory());
		assertEquals(ViewOptions.DEFAULT, optionsOf(config));
		assertTrue("and DEFAULT carries it", ViewOptions.DEFAULT.countInventory());
		assertEquals(RowFilter.DEFAULT, new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(),
			config.sortDescending(), config.window()));
	}

	/**
	 * Y1's exact words, and the one description in the group that has to say WHEN: the two containers are read
	 * at exactly two moments (a bank visit and Refresh, Y2), so a user who eats a shark and watches the row
	 * sit still has been told why before they ask. Its position puts it directly after "Include untradeable
	 * items", which is where the gear menu lists it.
	 */
	@Test
	public void theCarriedSwitchIsNamedAsTheGearMenuNamesIt()
	{
		final ConfigItem carried = item("countInventory");
		assertEquals("Include inventory and worn gear", carried.name());
		assertEquals("Items in your inventory and worn gear count in the bank value and are listed with the bank's"
			+ " stacks. They are read when you open the bank or press Refresh.", carried.description());
		assertEquals(12, carried.position());
	}

	/**
	 * Y4 as one list: the entries of the gear menu in the order it draws them, minus the Refresh item, the preset
	 * boxes, "Reset to default" and the OK button (which are the panel's, not config keys). The user asked for
	 * "more layman names" and agreed these, so they are pinned in one place as well as beside their own items - a
	 * rename here is a user-visible change and has to be a deliberate one.
	 *
	 * <p>Addendum AH added the last, in the same plain voice: "Show hover text". Addendum AI deleted it
	 * with the row hover it used to silence, and addendum AJ put it back a wave later because the card's hover
	 * and every control's were still on and still had no switch. It is drawn in the row ABOVE the bottom
	 * [Reset to default][OK] row, which is where the user pointed.
	 *
	 * <p>Nine became EIGHT with addendum AO line AO1: "Show stack value on rows" is gone from the menu and from
	 * the settings page both, so the list here is one shorter and the menu's view group now ends on "Include
	 * inventory and worn gear".
	 */
	@Test
	public void theGearMenusEightConfigItemsReadInTheWordsOfAddendumY()
	{
		assertEquals("Show bank value", item("showBankValue").name());
		assertEquals("Show change in gp", item("showBankMoveGp").name());
		assertEquals("Show change in %", item("showBankMovePct").name());
		assertEquals("Use live prices", item("livePrices").name());
		assertEquals("Include coins and platinum tokens", item("countCash").name());
		assertEquals("Include untradeable items", item("countUntradeables").name());
		assertEquals("Include inventory and worn gear", item("countInventory").name());
		assertEquals("Show hover text", item("showHoverText").name());
	}

	/**
	 * Addendum T line T1: the item it added is ON for a fresh profile, because the user chose that - "for the
	 * current 24 hour window if its accurate we should use live prices" - and because the switch only ever
	 * REPLACES a guide price where an item passed all three liquidity checks, so a default-on profile still shows
	 * the daily guide figure for everything thin. It is the THIRD field of {@link ViewOptions} since addendum AO
	 * line AO1 took {@code holdingOnRows} out from above it (it landed as the fourth), and not a
	 * {@link RowFilter} field: it changes what a figure IS, never which rows the band and the ordering select.
	 */
	@Test
	public void theLivePricesSwitchDefaultsToOnAndIsTheThirdViewOption()
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertTrue("live prices are on for a fresh profile (T1)", config.livePrices());
		assertEquals(ViewOptions.DEFAULT, optionsOf(config));
		assertTrue("and DEFAULT carries it", ViewOptions.DEFAULT.livePrices());
		assertEquals(RowFilter.DEFAULT, new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(),
			config.sortDescending(), config.window()));
	}

	/**
	 * T1's exact words. The gear menu's first check item in its last group and RuneLite's settings page are the
	 * same switch, so they are read in the same sentence, and the sentence is pinned here because it is the whole
	 * of what a user has to go on when deciding whether to leave it on: it says what qualifies ("actively traded")
	 * and what happens to everything else ("thin items keep the daily guide price"), with no jargon in between.
	 * The name is the one addendum Y line Y4 gave it ("Live prices" read as a heading rather than as a switch).
	 *
	 * <p><b>Its position is 14.</b> It has been the LAST item on the settings page since addendum T, and it still
	 * is - addendum AH inserted {@code showHoverText} above it and pushed it down, exactly as Z and AA renumbered
	 * the items below them; addendum AI took that item away and AJ put it back in the same slot; addendum AO
	 * deleted {@code holdingOnRows} from above both and moved this one back up from 15 to 14. Positions are not
	 * frozen and keys are, so this assertion is about the ORDER a reader meets the page in and about nothing
	 * stored: what it pins is that every arrival and departure above has been slotted in rather than appended
	 * past this one, and that the page has no gap in it.
	 */
	@Test
	public void theLivePricesSwitchIsNamedAsTheGearMenuNamesIt()
	{
		final ConfigItem live = item("livePrices");
		assertEquals("Use live prices", live.name());
		assertEquals("Actively traded items use the wiki's live traded prices for every figure;"
			+ " thin items keep the daily guide price", live.description());
		assertEquals(14, live.position());
		// ...and last means last: nothing on the page sits below it.
		for (Method m : itemMethods())
		{
			final ConfigItem other = m.getAnnotation(ConfigItem.class);
			assertTrue("\"" + other.name() + "\" is drawn below the last item on the page",
				other.position() <= live.position());
		}
	}

	/**
	 * Addendum AH, restored by addendum AJ: the key it added is OFF for a fresh profile, and it is the only item
	 * on this page whose default shows LESS than the build before it did. The user asked for exactly that - "i
	 * would like it to be default 'off' and only display hover text if turned on" (2026-09-20) - after addenda AF
	 * and AG had already cut both data hovers down, and asked for the switch BACK when addendum AI deleted it
	 * ("where is the show hover text box and wording and default 'off' setting?").
	 *
	 * <p>It is the FIFTH and last field of {@link ViewOptions} since addendum AO line AO1 took
	 * {@code holdingOnRows} out from above it (it landed as the sixth), and not a {@link RowFilter} field: it
	 * changes whether a figure is EXPLAINED, never what the figure is or which rows the band and the ordering
	 * select, so a fresh profile's filter is untouched by its arrival and flipping it refetches nothing.
	 */
	@Test
	public void theHoverTextSwitchDefaultsToOffAndIsTheFifthViewOption()
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertFalse("the hover text is off until the user asks for it (AH)", config.showHoverText());
		assertFalse("and DEFAULT carries the same answer", ViewOptions.DEFAULT.showHoverText());
		assertEquals(ViewOptions.DEFAULT, optionsOf(config));
		assertEquals(RowFilter.DEFAULT, new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(),
			config.sortDescending(), config.window()));
	}

	/**
	 * AH's exact words as addendum AJ narrowed them. The gear menu's last check item and RuneLite's settings page
	 * are the same switch, so they are read in the same sentence - the menu item IS this {@code name}, and its own
	 * hover IS this {@code description} (pinned against {@link BankPriceMovementPanel#SHOW_HOVER_TEXT_TEXT} and
	 * {@link BankPriceMovementPanel#SHOW_HOVER_TEXT_TIP}, because the words are written in two files and only a
	 * test can see both).
	 *
	 * <p>The description names the TWO places the switch reaches, and the list has moved twice. AH's first cut
	 * spared every tooltip that explains a control and said so here ("The buttons keep their own labels"); the
	 * user turned the switch off, was still met with hover text, and overruled it - "make sure there is no hover
	 * text at all unless it is on" - so AH3 widened it to three places, the item ROWS among them. Addendum AI
	 * then moved a row's description out of its hover and into the cell it opens, so a row carries no tooltip at
	 * ANY setting and is silent whether this is on or off. Naming the rows here would now be a promise the
	 * sidebar cannot keep in either direction, which is why the sentence is pinned WITH a guard against that word
	 * rather than only as a literal.
	 *
	 * <p>Position 13 - it was 14 until addendum AO deleted the item above it - puts it second to last on the
	 * settings page, directly above "Use live prices". In the gear menu it is drawn LAST of the switches, in the
	 * row ABOVE the bottom [Reset to default][OK] row - which is where the user pointed ("it should be the row
	 * above OK") - so this is one of the few items whose two homes do not list it in the same place, and the
	 * position pins the settings page's.
	 */
	@Test
	public void theHoverTextSwitchIsNamedAsTheGearMenuNamesIt()
	{
		final ConfigItem hover = item("showHoverText");
		assertEquals("Show hover text", hover.name());
		assertEquals("the settings page and the gear menu name it with one string (AH)",
			BankPriceMovementPanel.SHOW_HOVER_TEXT_TEXT, hover.name());
		assertEquals("Show hover text anywhere in the sidebar: the bank value and the controls",
			hover.description());
		assertEquals("and the menu item's own hover is that same sentence",
			BankPriceMovementPanel.SHOW_HOVER_TEXT_TIP, hover.description());
		assertFalse("the description must not promise the controls keep talking - AH3 silenced them too",
			hover.description().contains("keep their own labels"));
		assertFalse("...nor claim the item rows, which carry no hover at any setting since addendum AI",
			hover.description().contains("row"));
		assertEquals(13, hover.position());
		// Directly above the item that is last on the page.
		assertEquals(14, item("livePrices").position());
	}

	/**
	 * Z1: the fourteenth key holds the three quick bands as one line, and a fresh profile stores exactly the trio
	 * the fold has drawn since addendum N - which is what makes the default sidebar byte-identical to addendum W's
	 * (Z5). The literal in the interface and {@link BandPresets#DEFAULT} are pinned to each other here, because
	 * they are written in two places and only this test can see both: the interface body is what
	 * {@code ConfigManager} stores, and {@code BandPresets.DEFAULT} is what "Reset to default" and an unreadable
	 * line fall back to.
	 */
	@Test
	public void thePricePresetsDefaultToTheThreeBandsTheFoldHasAlwaysDrawn()
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertEquals("100k, 1m, 10m", config.bandPresets());
		assertEquals("the stored default is the value's own text", BandPresets.DEFAULT.format(),
			config.bandPresets());
		assertEquals("and it reads back as the default trio", BandPresets.DEFAULT,
			BandPresets.parse(config.bandPresets()));
		// Not a filter field: the chips APPLY a band, they are not one, so a fresh profile's filter is untouched
		// by their arrival (Z1).
		assertEquals(RowFilter.DEFAULT, new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(),
			config.sortDescending(), config.window()));
	}

	/**
	 * Z1's exact words as addendum AB line AB3 left them, and the one item on this page whose value a user types in
	 * full: the name is the grey label the gear menu's box row carries ("Preset price ranges" - the user's own
	 * words, and the pin that keeps the two places from drifting apart) and the description is
	 * the whole of the rule - three bands, in shorthand, smallest first - with an example of a line that obeys it,
	 * because there is no dropdown and no tick here to show the shape. The KEY is untouched by the rename, so a
	 * profile that stored a trio before it keeps it. Position 2 puts the item directly after "Max unit price (gp)",
	 * which is the pair it belongs to.
	 *
	 * <p>It has been renamed twice and the description not at all, which is the shape of both changes: Z7 and AB3
	 * are the user reading the CAPTION over three boxes and asking for fewer words each time ("Also change the
	 * wording from 'Quick price filter presets' to 'Preset price ranges'",
	 * {@code docs/bank-price-movement-addendum-AB-2026-09-13.md}), while the sentence under it - the rule - was
	 * right the first time.
	 */
	@Test
	public void thePricePresetsItemIsNamedAndDescribedAsAddendumZWroteIt()
	{
		final ConfigItem presets = item("bandPresets");
		assertEquals("Preset price ranges", presets.name());
		assertEquals("the settings page and the gear menu name it with one string (Z7, AB3)",
			BankPriceMovementPanel.PRESETS_TEXT, presets.name());
		assertEquals("The three quick bands under the band button, in gp shorthand and smallest first -"
			+ " for example 1m, 10m, 100m.", presets.description());
		assertEquals(2, presets.position());
	}

	/**
	 * AA1: the fifteenth key opens the fold on a fresh profile, which is the whole of the user's request - the
	 * three quick bands became editable one wave ago, and a control that starts folded away is a control nobody
	 * finds ("can you by default have 'All items' expanded too so the user can see the quick price presets on the
	 * main tab? ... currently it starts minimized").
	 *
	 * <p>It is not a {@link RowFilter} field and never could be: the fold is where a band is TYPED, so opening or
	 * closing it moves no row - {@code gpMin} and {@code gpMax} keep whatever was last applied, and a closed fold
	 * still filters. Nor is it a {@link ViewOptions} field: no figure is recomputed for it.
	 */
	@Test
	public void theFoldOpensByDefaultAndIsNeitherAFilterFieldNorAViewOption()
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertTrue("the price fold is open on a fresh profile (AA1)", config.foldOpen());
		assertEquals(RowFilter.DEFAULT, new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(),
			config.sortDescending(), config.window()));
		assertEquals(ViewOptions.DEFAULT, optionsOf(config));
	}

	/**
	 * AA1's exact words, in the nouns addendum AB line AB3 chose. The settings page is the ONLY place this switch
	 * is named - the sidebar's own way to it is the band button, which carries no label for the fold - so the name
	 * says what is shown ("Show preset price ranges") and the description says both halves: what stays open, and
	 * the gesture that closes it. It borrows the name of the item above it on purpose: AB3 renamed those three
	 * boxes and this row is the one that uncovers them, so the two rows a reader meets together name one thing.
	 * Position 3 puts it directly after "Preset price ranges", the item whose three bands it decides the
	 * visibility of.
	 */
	@Test
	public void theFoldItemIsNamedAndDescribedAsAddendumAaWroteIt()
	{
		final ConfigItem fold = item("foldOpen");
		assertEquals("Show preset price ranges", fold.name());
		assertEquals("Keep the preset price ranges and the Min / Max fields open under the sort button."
			+ " Clicking the band button folds them away or back.", fold.description());
		assertEquals("the row and the row it uncovers name one thing (AB3)",
			BankPriceMovementPanel.PRESETS_TEXT, item("bandPresets").name());
		assertTrue("...and this one says SHOW of those ranges",
			fold.name().startsWith("Show ") && fold.name().endsWith("price ranges"));
		assertEquals(3, fold.position());
		// Directly after the presets it uncovers, and directly before the sort column - the pair a reader meets
		// them as on the settings page.
		assertEquals(2, item("bandPresets").position());
		assertEquals(4, item("sortMode").position());
	}

	/**
	 * The five view switches read off a config, in the order and arity
	 * {@code BankPriceMovementPlugin.optionsFromConfig()} reads them - which is the point of doing it here rather
	 * than writing the constructor out in each test: five booleans in a row are five chances to pin the wrong
	 * round trip, and addendum AO line AO1 removed a field from the MIDDLE of that list, where a stale call
	 * would still have compiled.
	 */
	private static ViewOptions optionsOf(BankPriceMovementConfig config)
	{
		return new ViewOptions(config.countCash(), config.countUntradeables(), config.livePrices(),
			config.countInventory(), config.showHoverText());
	}

	/** The {@code @ConfigItem} of one stored key, by name; fails rather than returning null when it is gone. */
	private static ConfigItem item(String keyName)
	{
		for (Method m : itemMethods())
		{
			final ConfigItem item = m.getAnnotation(ConfigItem.class);
			if (keyName.equals(item.keyName()))
			{
				return item;
			}
		}
		throw new AssertionError("no config item declares the key " + keyName);
	}

	/**
	 * W4: the config panel and the sidebar are the same switch, so they speak the same language - and since
	 * addendum W that language is four column names, which the settings dropdown prints for itself
	 * ({@link SortMode#toString()} is the label). So the description's job is only to name the set, and to teach
	 * the one gesture a settings page cannot show: pressing the lit column again flips the direction.
	 *
	 * <p>This replaces the test that demanded every one of addendum N's six invented sentences ("Biggest
	 * gainers", "Cheapest") appear here. They existed because the pair was undiscoverable in the sidebar; the
	 * arrow of W2 is what made it discoverable, and the sentences went with the {@code SortOrder} enum.
	 */
	@Test
	public void theSortColumnItemNamesTheFourColumnsAndTheFlip()
	{
		final ConfigItem sort = item("sortMode");
		assertEquals("Sort column", sort.name());
		assertEquals("Which column the list is ordered on: Percent change, gp change, Item price or Stack price -"
			+ " pressing the lit column again in the sidebar flips the direction.", sort.description());
		assertEquals(4, sort.position());
		// ...and the four are the four the enum has, so a column added later cannot go unnamed here.
		for (SortMode mode : SortMode.values())
		{
			assertTrue("the settings page never names the sidebar's \"" + mode.label() + "\":\n"
				+ sort.description(), sort.description().contains(mode.label()));
		}
	}

	/**
	 * W4, the direction half: "Biggest first" is the arrow the sort button draws (W2), and the arrow is the only
	 * place the direction is visible in the sidebar - so the description names BOTH ends of the switch and which
	 * way the arrow points at each, rather than leaving a user to tick it and go and look.
	 */
	@Test
	public void theSortDirectionItemNamesBiggestFirstAndTheArrow()
	{
		final ConfigItem descending = item("sortDescending");
		assertEquals("Biggest first", descending.name());
		assertEquals("On is biggest first and the sidebar's arrow points down; off is smallest first and it"
			+ " points up. Items with nothing to sort on always come last.", descending.description());
		assertEquals(5, descending.position());
	}

	/**
	 * O1: the {@code look} item is gone with the One Bar design, and so is its enum. A profile written by the
	 * addendum-N build may still hold the KEY - {@link BankPriceMovementPlugin#unstickLook()} sweeps it at
	 * startUp - but no item here may ever declare it again, or a stale design name would be read back into
	 * something new.
	 */
	@Test
	public void theLookItemIsGone()
	{
		for (Method m : itemMethods())
		{
			assertFalse("the look config item was deleted by addendum O (O1)",
				BankPriceMovementPlugin.LEGACY_LOOK_KEY.equals(m.getAnnotation(ConfigItem.class).keyName()));
		}
	}

	/**
	 * AO1, the same guard for the second key this plugin has deleted for good: {@code holdingOnRows} ("Show stack
	 * value on rows", addendum Q line Q3) chose whether a row printed the per-ITEM reading or the per-STACK one, and
	 * addendum AN's three-line row prints both - line 2 is the stack, line 3 is one item - so the switch reached
	 * nothing drawn and the user, asked whether to keep it, answered "if it doesnt do anything anymore then
	 * remove it".
	 *
	 * <p>A profile written before addendum AO may still hold the KEY -
	 * {@link BankPriceMovementPlugin#unstickHolding()} sweeps it at startUp, exactly as
	 * {@link BankPriceMovementPlugin#unstickLook()} sweeps addendum N's - but no item here may ever declare it
	 * again. This file is where a deleted key coming back by a merge is caught: the set assertion above would
	 * fail too, and would be read as "somebody forgot to update the list", which is the wrong reading and the
	 * reason this test says which addendum took it and why.
	 *
	 * <p>Nothing replaces it. The one behaviour it still had is now unconditional: {@link SortMode#GP_MOVE}
	 * compares {@code MovementRow.holdingDeltaGp()} - the whole STACK's move - under every setting, which is
	 * what the switch gave when it was ON rather than the per-item reading it defaulted to. That is a
	 * user-visible change to one column's ordering, and there is no key left to undo it with.
	 */
	@Test
	public void theHoldingSwitchIsGone()
	{
		for (Method m : itemMethods())
		{
			assertFalse("the holdingOnRows config item was deleted by addendum AO (AO1)",
				BankPriceMovementPlugin.LEGACY_HOLDING_KEY.equals(m.getAnnotation(ConfigItem.class).keyName()));
		}
		assertFalse("...and no item may declare it under the old name either",
			EXPECTED_KEYS.contains(BankPriceMovementPlugin.LEGACY_HOLDING_KEY));
	}
}
