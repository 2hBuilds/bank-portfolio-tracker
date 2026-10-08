# 2h Bank Portfolio Tracker

> This is the full description of 2h Bank Portfolio Tracker - every figure, setting and rule. The short
> version, with pictures, is the plugin's page on the Plugin Hub (the repository's README).

Your whole bank, priced, with what each item's Grand Exchange price has done over the last day, week, month,
quarter or half year - a figure from the last 24 hours' trades for every item that has traded, the guide price
for the rest, and your inventory, worn gear and Grand Exchange offers counted in. It also has a **Net Worth History**: your bank's own
total, one reading a day.

## What the sidebar shows

**The Bank value card**, at the top, is your whole bank as one figure:

- the **total** - every stack that has a price, `unit price x quantity`, summed, **plus your coins and
  platinum tokens** (1,000 gp each), and by default what you are carrying and wearing too (see below). It counts
  the same kinds of items RuneLite's own Bank plugin counts in the bank window's title bar - tradeable stacks,
  coins and platinum tokens, and untradeables made from tradeable items - but the two are not the same number:
  the prices come from different places, and the title bar totals whichever bank tab you are looking at;
- the **gp move** and the **percentage** for the window you have lit, computed over the stacks that had a price
  on *both* days, so the two ends of the comparison are the same basket;
- the **window chips** `1d | 7d | 30d | 90d | 180d` - click one and the whole panel follows it;
- a **provenance footnote** ("1d vs 08 Sep - bank 09:00") saying which day the figures are measured against and
  when the bank was last read;
- **hover the card** and you get the total written out to the last gp, commas and all - *"446,901,681 gp"* -
  which is the one figure the rounding on the card takes away, and under it which guide table the window is
  measured against, by its time: *"1d vs 06 Oct 21:32 UTC (table)"* (the footnote has room for the day only, and
  the wiki now saves several tables a day). With *Use live prices* on, when some of the rows are soft (see *Live
  prices*) the hover also says how many are solid - *"412 of 497 rows solid"*. Everything else is already on the
  card or one chip away, so the hover does not repeat it;
- a last line reading **"Guide prices update every few hours"**, because Jagex publishes the guide price several times a day
  and that is how often any of these figures can move - hover it for why, and for when the plugin last checked;
- a **Refresh** link, and one click refreshes everything: **your items at once** - your bank as it is now if it
  is open (see *While you bank*, below), and what you are carrying and wearing - and **the prices in the
  background**. The prices are re-checked at most once every 30 seconds, so a click sooner than that updates only
  your items: with the bank open it does so quietly, and with the bank closed a line under the controls says
  *"Refreshed n s ago - wait"* - that line, like any other problem, prints there rather than interrupting
  anything. Guide prices only move several times a day, so a refresh usually brings the same figures back -
  hovering the line above tells you when the prices on screen were last read, so you can tell "nothing changed"
  from "nothing happened". A click is answered in two words: *Refreshing...*, then **Up to
  date**, which fades back to *Refresh* on its own a minute later;
- a small **settings icon**, in the top-right corner of the card, with a **Discord mark** beside it on every card
  that opens the 2hBuilds Discord, which opens the panel's **Options** menu: *Refresh
  prices now* (the prices alone, whether your bank is open or not), the three switches for the card's own
  figures, the four that decide what the panel counts and which stacks get a row (see *Settings* - they are the
  same switches as RuneLite's settings page, so either place works), then the three **Preset price ranges**
  boxes. Along the bottom sit two buttons: **Reset to default** on the left, which puts *100k / 1m /
  10m* back into the preset boxes, and **OK** on the right, which closes the menu - it takes the boxes with it
  on the way out, which closing the menu does anyway, since everything in here saves itself as you set it.
  Clicking the settings icon again while the menu is open closes it too: the icon is a switch, not just a way
  in. The menu opens with the plugin's name and which version of it you are running, as its header, and under the
  version a row of marks for the 2hBuilds Discord, X and GitHub pages and this plugin's own GitHub page.

The gp band you set does **not** apply here: a portfolio is everything you own, coins included. Each of the
three figures has its own switch, and a switch you turn off **removes** the line rather than blanking it - the
card shrinks. Turn the **total** itself off and its hover goes with it, since there is no longer a number there
to write out in full.

**One row per GE-tradeable stack.** A row is the item's picture and three lines:

```
Divine ranging poti...(3)
32.3k            +3.0k    +10.2%
7 x 4,618        +428
```

- **The name**, on its own line.
- **What the whole stack is worth**, and what it did. `32.3k` is the value of everything you hold of that item -
  the big figure on the row. Beside it: what that stack **made or lost** over the window you have lit, and the
  **percentage**. Green for a rise, red for a fall, grey for a flat row.
- **The working underneath it.** `7 x 4,618` is how many you hold and what one of them costs - multiply them and
  you get the line above, which is how you can always tell the big figure is the whole stack rather than a price
  per item. At the end of that line is what **one** item made or lost. (Hold exactly one of something and this
  figure is left off, because it would be the same number as the one above it.)
- **a coloured left rail** on movers only - a flat row, a row with no baseline and a row with no price carry none.

The two gp figures sit in a column, the stack's directly over the item's, so you can run your eye down the page
on either. The percentage is the biggest figure on the row and the gp figures are drawn a shade quieter - the
same green or red, just turned down - because they say the same thing in two units and a list of two hundred rows
should not ask you to choose between them on every line. A flat row prints no gp figures at all, just a grey
`0.0%`. A move of 100 % or more is written in whole percents (`+157%`), and from 10,000 % in thousands
(`+12k%`), so it always fits its column; the block the row opens into keeps the exact figure.

**A word under the percentage** (1.2.0). With *Use live prices* on, a figure built on thin trading looks exactly
like any other - the same bold percentage, the same colour, the same gp figures, the same rail - and a small grey
word sits under the percentage to say why you might lean on it less: `last 1d ago`, `low vol 3` or `spread 55%`. A row
with no word is solid. An item with nothing to measure prints a dash where the
percentage would be, and `no trades` under it. *Live prices*, below, says what each word means. *Hide amounts* leaves
the words alone, since they are not amounts.

**The gp figures on the row are rounded** the way every other figure here is: a change of 3,432 gp reads `+3.4k`.
Click the row for the exact number.

- **click the row and it opens.** The row grows, and the detail appears underneath it, inside the same card, as
  four labelled lines:

  ```
  Worth now    4,618 gp each
  Was          4,190 gp  (19 Sep 21:32 UTC)
  You have     7  =  32,326 gp
  Change 1d    +428 each  +10.2%
  ```

  The time on the *Was* line is the guide table's own, in UTC like the date beside it: the wiki saves several
  tables a day, so the date alone no longer says which one the row was compared against. A row measured from
  the last 24 hours' trades compares against a whole day's trading and shows that day alone.

  **Change** always names the window you are on - *Change 1d*, *Change 7d*, *Change 30d* - so the figure says
  what it is measuring without you looking back up at the chips. Under those four sit only the lines that have
  something to add: that the item is untradeable and priced at its parts or its alch value, where the stack is
  split between your bank, your inventory and what you are wearing, and - for a row measured from the last 24 hours'
  trades - one sentence on what the figure was made from, then the last price each side printed and when (*"last
  sold 1,250 at 14:02"*). The sentence is one of five:

  - *"Buyers paid 1,338 in the last 24 hours vs 2,280 on average yesterday (-41 %); sellers got 1,250 vs 2,176 (-43 %).
    Both sides agree."* - a figure reported on both sides;
  - the same, ending *"... the sides disagree, so the move is uncertain."* - which does not make a figure soft; it
    only says the buyers' and the sellers' moves are more than 10 percentage points apart;
  - *"Buyers paid 1,338 in the last 24 hours vs 2,280 on average yesterday (-41 %); no sells in the last 24 hours."* -
    a figure reported on one side only, which the row is priced at (and the same with the sides the other way round);
  - *"No trades in the last 24 hours; this is yesterday's move (06 Oct vs 05 Oct)."* - the `last 1d ago` figure;
  - *"No trades in the last 24 hours or yesterday; valued at the guide price."* - the `no trades` row.

  A `spread 30%` row adds one more sentence at the end of the first two: *"The buy and sell prices are 30% apart."* -
  what the number in the word means, the latest buy and sell prices (both from the last 24 hours) against their middle.

  An untradeable held at its alch value has no *Was* and no
  *Change* at all - there is no earlier price for it, so the lines are simply not there rather than showing a
  dash. The item's full name is printed in bold at the top **when the row was too narrow to show it whole**, and
  left off when you can already read it on the row.

  Click it again and the row folds back to its usual height. It opens as tall as it needs to be and pushes the
  rows under it down - nothing covers anything, and it stays open while you read it and scroll past it.
- Open as many rows as you like. Each one remembers, so refreshing, re-sorting, changing the window or setting
  a price band leaves them open. Nothing in the panel opens a hover over a row - the detail is on the page, one
  click away, which is also where you read an item's name when it is too long to fit on the row.
- **right-click** a row for *Open on the Grand Exchange* (the item's page on `secure.runescape.com`) or *Open
  price history on the wiki*.

**The control row** under the card, one button at each end:

- on the **left**, a **band button** that states its own band ("All items", "1m+", "100k - 5m") and folds the
  **price fold** away
  or back. The fold is **open when you first install the plugin**: presets *All / 100k+ / 1m+ / 10m+* over a Min
  and a Max field, sitting under the controls. They accept `100k`, `1.5m`, `2b`, `1,000`; an empty field means no
  bound; text the parser refuses turns the field red and changes nothing. The band filters on the **unit** price.
  Click the band button to fold the whole strip away if you want a shorter header - the choice is remembered, and
  *Show preset price ranges* in the settings does the same thing. **The three presets are yours to set**:
  *Preset price ranges*, at the foot of the Options menu (behind the settings icon in the card's top-right corner),
  carries a box for each, and typing a new amount into one re-cuts that chip - so a big bank can read *1m+ /
  10m+ / 100m+*. *Reset to default*, bottom left of that menu, puts *100k / 1m / 10m* back.
- on the **right**, a **sort button** naming the column the list is ordered on, with a small **arrow** for the
  direction: down for biggest first, up for smallest. Click it for the four columns - *Percent change*, *gp
  change*, *Item price*, *Stack price* - and click the lit one again to flip it; a column you have just picked
  always starts biggest first. *Item price* is what one of the item costs; *Stack price* is what the whole stack
  is worth (price x quantity), so a hundred robin hood hats climb above one item that costs more each. *gp
  change* is what the whole **stack** made or lost - the figure on the row's second line - so a big pile of a
  small mover beats a single item that moved further. Every one of those figures is printed on the row, so
  whichever you pick, the list agrees with something you can read. A soft figure (one with a word under its
  percentage) sorts by its figure like any other - the word is there to tell you how much to lean on it. Rows with
  nothing to sort on come last in either direction.

Rows come in pages of 250 with a "Show *n* more" button under them, so an 800-item bank does not freeze the
sidebar. If a band matches nothing, the panel says so and offers *Clear price range* in one click.

**Search** (1.0.9). Directly above the first item sits a box reading *Search items*. Type part of a name and the
list keeps only the matching rows as you type - "rune" finds Rune platebody and Runite ore - on top of whatever
band and sort are set; the count under the card says how many matched. Empty the box (press the small x at its
right end, which appears while it holds text, or press Escape in it) and the whole list is back. It is not
remembered between sessions, and it works on the list the panel already has, so typing never touches the game.

**List options** (1.1.0). At the right end of the search box sit two small gears. Press them for a menu with *Show
alch-only items* and the *Up colour* and *Down colour* swatches (the same two as in the settings menu). Alch-only
untradeables never move, so by default they are not listed; tick the box to list them, always after every other
row, whichever column you sort on. Searching finds an alch-only item either way. The tick changes the list and the
count under the card and nothing else: what the Bank value and the Net Worth History count is still decided by
*Include alch-only untradeables*.

## Net Worth History

Under the Bank value card sit two buttons, **Items | Net Worth History**, with one grey line beneath them saying
what the panel is showing: *Item price changes* for the list of your items, *Bank net worth history* for this
view. They appear once the panel has your bank, and the panel opens on whichever of the two you used last. In the
Net Worth History the sort and band buttons and the price ranges under them step
aside - they order and filter the item list, which the Net Worth History does not show.

**The Net Worth History is your bank's own total, one reading a day** - the Bank value figure, kept for every
day you log in. Each time the total is worked out that day - when your bank is read, when you log in, when you
open the panel or press Refresh, and every 30 minutes while the panel is on screen - the day's reading is
brought up to date, so the last one of the day is the one that stays. Past days never change. A day you did not
log in has no reading of its own: the panel carries your last reading across it rather than guessing. Days are
your own local dates, and nothing is recorded while you sit at the login screen.

**The card** keeps its place and its size, but in the Net Worth History it compares your total now with the
total you actually had: *30d vs your 27 Aug total*. If there is no reading on that exact day, it uses the one
before it and says how far back that is: *1d vs your 25 Sep total (3 days)*. A window your record is not long
enough for yet is greyed, its move reads a dash, and the footnote says when it fills - *30d from 17 Oct* -
though you can still click it. Instead of *Guide prices update every few hours*, the last line says how long your
record is: *37 days recorded since 18 Aug*.

**The chart** starts with the change over its range and the days it spans (*27 Aug - 26 Sep +24.2m +3.4%*), then
a line naming a day and its total to the last gp, then the chart itself: a line of your total, in the 2h logo's
gold (*Single chart colour*, on by default, with any colour you pick), or - with that switch off - in your up colour
when the range rose and your down colour when it fell, over a shading of the same colour that fades out towards the
bottom, with the
range's highest and lowest reading marked by a small grey dot and their short totals (*759m*, *712m*), and your
latest reading by a dot in a soft glow. Rest the pointer on the chart and the line above it names the day under
it; move away and it goes back to your latest reading. A day with no reading carries your last total across,
drawn like any other day, so the line never breaks. Under the chart, a bar of four chips - *7d*, *30d*, *90d*
and *all*, the one in use filled orange - picks its range. Picking a window on the card moves the chart with it
(*1d* and *7d* show the last 7 days, *180d* shows everything); the chart's own chips move the chart and nothing
else. A chip whose range reaches back before your first reading is drawn grey, and picking it shows the change
since your first reading. Over a long record the chart draws at most 120 points, each the last reading of its
few days.

**The list of days** under the chart has every day since your first reading, newest first, whatever the chart
shows: the date, your total (short and to the gp), and the change since the reading before - the percentage in
bold, the gp a shade quieter, and under the date the day it is compared with (*vs 25 Sep*, or *vs 21 Sep, 2
days* across a gap). Your first reading ever says *first reading*. A day with no reading is a thin grey line
with its date alone - *Tue 22 Sep* - while the chart carries your total across it. A day more than 300 days ago
shows its year. The days come in pages of 250, like the items.

**It adds up the way the card does.** Every reading is saved in parts - what was in your bank and what you were
carrying, each split into tradeable items, coins and platinum tokens, untradeables made from tradeable items,
and alch-only untradeables, and since 1.0.9 the items and the coins in your Grand Exchange offers - so the Net
Worth History always counts exactly what your settings count. Readings saved before 1.0.9 did not count offers,
so the tracker hides them by default and your first bank read on 1.0.9 is day one. *Include days before v1.0.9*,
in the settings menu's *Net worth chart* section, shows them again after a short confirmation (those days did not count open G.E.
orders, so their totals may read low); it appears only if you have such days, and it is remembered. Nothing is
deleted. The same happens once more in 1.1.1 for players whose bank placeholders were counted as items (see
*What is left out of the list* below): if your first bank read on 1.1.1 finds any, the days before it read high
and are hidden behind *Include days before v1.1.1*; if it finds none, nothing is hidden. Turn off
*Include coins and platinum tokens* and the whole line redraws without coins, past days included; it never shows
up as a one-day loss. The two small cogs at the right end of the *Bank net worth history* line open *History
options* - *Include days before ...* (only while such days exist) and *Single chart colour*, the same two items as in the
settings menu - and while the days before v1.1.1 are hidden, a small *i* disc beside the cogs says on hover that they
might read high because of the bank placeholder bug and that you can restore them in settings; pressing it asks the same
*Include* question as the menu item, and it goes away once you include them. A reading is priced the way the card priced it that day, and the guide-price figure is
saved beside it, so turning *Use live prices* off redraws the line on guide prices alone. A day saved with live
prices off has only its guide figure, so turning them on later can show a small step at the first live day.

**Before your first reading** the view says *No readings yet - open your bank to load your first reading.* On
your first day the chart shows that one point, and the list its one row.

One thing to know: if you stay logged in past midnight with the panel closed and your bank untouched, the new
day gets no reading until your bank changes, you press Refresh, open the panel or log in again - until then that
day shows as a day with no reading.

Your history is kept on your computer, one small file per account and profile (see *Where its files live*), and
is never uploaded.

## While you bank

A quick gear swap should cost you nothing, so the panel keeps still while your bank is open:

- **The list does not redraw while your bank is open.** Deposit and withdraw as much as you like - nothing is
  re-read and nothing is rebuilt until you are done. Anything you click in the panel itself - a window chip, the
  sort button, a price band - still answers at once.
- **A thin green ring breathes round the Refresh link** when your bank, or what you are carrying or wearing, has
  changed since the list last read it - a sign the list is behind. It breathes from faint to full over three
  seconds and back out over three, again and again until the list catches up, and it only shows while the panel
  is on screen.
- **The list catches up once**: when you close the bank, or straight away if you click the glowing *Refresh*
  with the bank still open - which re-checks the prices as well, as every click on it does. The ring goes out
  either way.
- **The first time you open your bank after starting the client** - or on another account - it is read as it
  opens, because there is nothing earlier to compare it with.

The time at the end of the card's footnote (*bank 09:00*) is when your bank was last read, not when you last
opened it.

Clicking a row puts its detail together at that moment, rather than for every row in advance. Nothing you see is
different - it is simply part of why the list redraws quickly.

## Hover text

**Hover text is always on.** Rest the pointer on something and the panel explains itself: the bank value gives you
the exact total to the last gp, and the sort button, the band button, the Refresh link, the *"Guide prices update
every few hours"* line and every item in the Options menu say what they do. Each one is a single short line. The
Refresh link's hover reads *"Re-read your items and re-check the prices. Jagex publishes guide prices several times
a day."* - the same wherever you are, because a click does the same thing everywhere.

Version 1.2.0 took away the **Show hover text** switch that used to sit in the Options menu, off by default: every
hover is one short line, so a setting to hide them was one more than the panel needs.

**An item row never opens a hover.** Its detail is not a tooltip - you **click the row** and it opens on the page,
under the row's own line, and stays there until you close it. That is also where you read an item's name when it is
too long to fit on the row.

The rows you leave open are remembered while you use the panel.

## Where the numbers come from

**"Now" is the Jagex GUIDE price** - the number the in-game Grand Exchange, the GE web site and RuneLite's own
tooltips show. It comes out of RuneLite's price table, which the client already keeps up to date, so it costs
no request at all. With *Use live prices* on, the items that have traded in the last 24 hours are the exception - see
*Live prices*, below.

**"Then" is the same table as it stood a whole window earlier.** The OSRS wiki republishes Jagex's guide prices
as the page `Module:GEPrices/data.json` - once a day until the end of September 2026, and about eight times a day
since, each time Jagex moves its prices. The plugin keeps the list of those saves (the page's revision history:
every save of the last eight days and one a day before that, back more than a year) and works out **which of them
RuneLite's prices are** - "now" is that table's own time stamp. Each window then compares against the **newest
table at least that many days older**: 1d against the table that stood 24 hours before, 7d against the one 7 x 24
hours before, and so on. So a 1d move is always a day's move, never the hour or two between two of the wiki's
saves. When RuneLite already has a table the wiki has not listed yet, "now" is the current time instead, and the
plugin checks the wiki's list again on its next half-hourly check rather than waiting the usual six hours; when
RuneLite is a step behind the wiki, the windows count back from the table RuneLite holds. The labels show the
date of the table each window uses ("1d vs 06 Oct"); the card's hover and an opened row add its time. The plugin
fetches the tables it needs in a single batched request - **a few small requests a day whatever your bank
holds**, and there is no per-item lookup, ever. Item names are joined to item ids through the wiki's
`prices.runescape.wiki/api/v1/osrs/mapping` table, fetched at most weekly.

**Percentages truncate toward zero**, exactly as the GE site's do, and take their sign from the gp change: a
fall too small to survive the truncation still reads `-0.0%` in red, as the site prints it. The site shows whole
percents and this shows one decimal of the same truncation, so "-3%" there is anything from "-3.0%" to "-3.9%"
here - never a bigger number, and never a different sign. (A row's face drops the decimal from 100 % up, as
described under the row; the truncation and the sign rule are the same.)

**What is left out of the LIST:** coins, platinum tokens, bank placeholders, bank fillers and anything the
Grand Exchange does not list, apart from the untradeables described below. Noted stacks fold onto the item they
note, and duplicate stacks of one item are summed. Coins and platinum tokens get no row because their price never
moves - but they *are* counted in the Bank value card, at face value and 1,000 gp each, because they are part of
what your bank is worth. That is also why a cash-heavy bank shows a smaller percentage than its items do: the
cash sits in the denominator and does not move. If you would rather read your bank as the items alone, turn off
*Include coins and platinum tokens* in the Options menu.

**Untradeable items** have no guide price of their own. One that RuneLite can take apart into tradeable items
is **always counted**, at what its **tradeable parts** are worth - crystal armour at its crystal armour seeds
(three of them for a body), a slayer helmet at its black mask, a Bow of Faerdhinen at its inactive form - and
such a row carries a real gp and percentage move, because the part it is made of has a guide price that moves.
With *Use live prices* on, its figure is its parts' figures added up, and it wears the word of its weakest part.
If one of its parts has no price on a given day, it is treated as alch-only for that day. The rest - graceful,
void, barrows gloves, your fire cape - have only a **High Alchemy** value, so they are left out of the Bank value
by default. Turn on *Include alch-only untradeables* and each of those is counted in the Bank value, at its alch
value. Whether they also get a row in the list is a separate choice, made in the *List options* menu (see *Search*
above): off by default. A row at an alch value is tagged *alch* where the movement figures would be, and listed after
every other row. The ones at an alch value never appear in a percentage or a gp move: there is no earlier price to
compare an alch value with.

**What you are carrying counts too.** *Include inventory and worn gear* is **on by default**: the items in your
inventory and the gear you are wearing are valued and listed exactly like the bank's own stacks, by the same
rules - noted stacks fold onto the item they note, coins and platinum tokens in hand are worth face value and
1,000 gp each, and an untradeable you are wearing is counted by the same rules as one in your bank.

They are read together with your bank - **when you open it or close it, and when you press Refresh** - and at no
other time. Nothing is watched in between - eat a shark with the bank closed and the row sits still until one of
those happens.
That is deliberate: reading two containers on every inventory change would be work on the game's own thread for
a number that moves back a second later.

An item you hold in **both** places is **one row** with the quantities added together, and clicking the row
names the split under its figures: *"3 in bank, 1 in inventory, 1 worn"* (a
worn-only item just says *"1 worn"*). The Bank value card counts the lot.

**Your Grand Exchange offers count too.** *Include Grand Exchange offers* is **on by default** (1.0.9): what is
sitting in your eight offer slots is yours and is counted - the items of a sell offer that have not sold yet, the
items a buy offer has bought that you have not collected, the coins a sell offer has earned that are waiting to be
collected, and the coins still committed to a buy offer (including the change a cheaper fill hands back). Items
are valued like the inventory's, at the item's price; coins are coins and follow *Include coins and platinum
tokens*. The offers are read at the same moments as your inventory - when the bank closes or you press Refresh -
and never on their own: an offer that fills with the bank closed shows at the next of those moments. A stack in
your bank and in an offer is one row, and the split reads *"3 in bank, 2 in the Grand Exchange"*. This is what
closed the gap where logging out with items on the Grand Exchange left the day's net worth short by their worth.

One consequence worth knowing: **RuneLite's own bank title bar will read lower than this card**, by roughly what
you are carrying and wearing, because it counts the bank container and nothing else. Turn the switch off and
every figure here is the bank alone, as the title bar's is.

If the wiki cannot be reached, the rows keep their prices - they are RuneLite's, not the wiki's - and lose only
their movement; the status line says so in grey, and nothing is red about it.

## Live prices

The guide price above is Jagex's, and Jagex publishes it **several times a day**. That is the right number for most of a
bank, but it means a Refresh usually changes nothing - and for the items that really are being bought and sold
all day, it can be hours out of date. So there is a switch, **Use live prices**, and it is **on by default**.

With it on, **every item that has traded in the last 24 hours gets a figure from those trades** - the wiki's live
traded series - and a Refresh really does move it. The figure is the item's move since the day your window points at: 1d
is yesterday, and 7d, 30d, 90d and 180d use the day that many days back (clicking a row names the day it used).
An item that has not traded keeps the guide price. Nothing is hidden: a figure built on thin
trading says so, in one word, under its percentage.

**How a figure is made.** Three rules:

- **Side with side.** Every trade has a buyer and a seller, and their prices sit a little apart. Compare the
  latest price with a day's average of both and you read that gap as a move, and it flips every time the last
  trade changes side. So the last 24 hours' buys are compared with yesterday's buy average, the last 24 hours' sells
  with yesterday's sell average, and the figure is the mean of the two moves (or the one, when only one side has
  traded).
- **The middle of the last 24 hours.** "Today" is the last 24 hours and not the calendar day. Each side's "now" is
  the middle price (the median) of what the plugin has seen of that side in those 24 hours - the latest price, the
  prices it saw on its earlier checks (kept in memory only, and forgotten once they are a day old) and the last closed
  hour's average - so one odd trade cannot carry it.
- **Stray prices are dropped.** A price more than three times, or less than a third of, the item's usual price
  (the middle of its guide price and yesterday's two averages) is ignored - a 1 gp print on a 1m item, say.

**The price on the row** is yesterday's average, the middle of its buy and sell averages, moved by the move. So a
row's worth, its gp figure and its percentage always agree with one another; the price follows the last 24 hours'
trades, but it cannot jump by the gap between buyers and sellers when the last trade changes side; and on a busy item
it lands within a hair of the live middle price. The Bank value and the Net Worth History add up these prices. A
row's price is the same whichever window is lit - the window changes what it is compared with. An item with no figure
is priced at the guide.

**Reported on one side only.** If only buyers (or only sellers) have traded in the last 24 hours, the row is priced at
that side's own price - if all the trades were buyers paying that, that is the current price - and compared with that
side's own average on the day it is compared with, so its worth, its was and its change still agree. It is no weaker
for it and carries no word of its own - it never says `spread 55%`, since the other side's last price is a day old or
more - and the open block says that the other side was silent. When the other side's first trade is reported the row
moves to the middle of the two - once.

**Solid or soft.** A figure is **solid** when it rests on real trading: a row with no word rests on trades from the
last 24 hours, at least 10 traded on the day it is compared with, and a buy/sell spread under 10 %.

A figure is **soft** in exactly three cases, and the small grey **word under the percentage** names the first that
applies. A soft figure is drawn **exactly like a solid one**; you still see the figure, and you see how much to lean on
it.

| Word | What it means |
|---|---|
| `last 1d ago` | Nothing has traded in the last 24 hours, so the figure is yesterday's move: yesterday's average against the day before's (on a longer window, against that window's day). Always soft. The word is how long it has been since the item last traded - whole hours under a day (`last 5h ago`), whole days from a day on (`last 1d ago`, `last 2d ago`). The words from `last 10h ago` to `last 23h ago` are drawn a little smaller to fit. When the plugin has no time for the item's last trade, the plain word `yday` stands in. |
| `low vol 7` | Fewer than ten of the item changed hands on the thinner side on the day it is compared with - the number is that count. |
| `spread 55%` | The latest buy and sell prices - both from the last 24 hours - are more than 10 % of their middle apart, and the number is how far (55 % here). A side that has been silent for a day makes the pair no spread, so an item reported on one side only never says it. The word is drawn a little smaller to fit. Click the row for the sentence that explains it. |
| `no trades` | There is no figure - nothing traded in the last 24 hours, and nothing from yesterday to fall back on, or no earlier price on the day to compare the last 24 hours' trades with. The row prints a dash and no gp figures, is valued at the guide price, and sorts last. This is a label, not a warning. |

Everything else is solid: the money traded, the quiet of the last hour, how many prices the plugin has seen, the
price of the item and whether the buyers' and the sellers' moves agree no longer matter.

A row on `last 1d ago` is priced at yesterday's average, since that is all there is to price it from.

**Sorting.** One list: a soft figure sorts by its figure like any other, in either direction, and only the rows with
no figure (the dash) come last. The word under the percentage is what says how much to lean on a figure; the order
does not.

**The card.** It looks the same. Hovering it adds how many rows are solid (*"412 of 497
rows solid"*) when some are soft, so you can tell a bank whose move rests on solid rows from one that does not.

**If the traded prices are out of date.** When the plugin cannot reach the wiki and its latest traded snapshot is
more than six hours old, nothing is graded and every row is on the guide price, as with the switch off. A window
whose daily figures the wiki has not delivered yet shows a dash until they arrive.

**Why it is built this way.** Pricing everything by its latest print put cheap, thinly traded items at the top of
*Percent change* on the strength of a single odd trade, and sending those items back to the guide left them on a
number that could be hours old. Here every item that traded gets a figure, the side-with-side rule stops the gap
between buyers and sellers passing for a move, and a figure that is thin says so in one word.

**Turn it off** (the Options menu behind the settings icon, or RuneLite's settings) and the plugin is exactly
the guide-price plugin it was before: one series for everything, no traded requests made at all, and every
figure matching the Grand Exchange website. That last point is the reason to turn it off - **the GE website
shows the guide price, so the two only agree with this switch off**. With it on, the line at the foot of the
card says so: *"Live prices on - others from the guide"*.

## Settings

Sixteen items, and every one of them is also a control in the sidebar: change it in either place and the other
follows.

| Setting | What it does |
|---|---|
| Min unit price (gp) | Hide items whose unit price is below this. 0 = no lower bound. |
| Max unit price (gp) | Hide items whose unit price is above this. 0 = no upper bound. |
| Preset price ranges | The three quick bands under the band button, in gp shorthand and smallest first - for example 1m, 10m, 100m. |
| Show preset price ranges | Keep the preset price ranges and the Min / Max fields open under the control row. Clicking the band button folds them away or back. On by default. |
| Sort column | Which column the list is ordered on: Percent change, gp change, Item price or Stack price - pressing the lit column again in the sidebar flips the direction. |
| Biggest first | On is biggest first and the sidebar's arrow points down; off is smallest first and it points up. Items with nothing to sort on always come last. |
| Movement window | How far back the guide-price change is measured: 1d, 7d, 30d, 90d or 180d. |
| Show bank value | The whole-bank total on the card. |
| Show change in gp | The bank's gp change for the chosen window. |
| Show change in % | The bank's percentage change for the chosen window. |
| Include coins and platinum tokens | Coins and platinum tokens (1,000 gp each) count in the bank value. On by default. |
| Include alch-only untradeables | Counts untradeables with no tradeable parts, at alch value, in the Bank value and the Net Worth History. Off by default. It does not decide whether they are listed - see *Show alch-only items*. Untradeables made from tradeable items always count, at their parts' prices. |
| Show alch-only items | Lists untradeables with no tradeable parts, at alch value, after the other rows. Off by default. Searching finds them either way. The tick is in the *List options* menu at the end of the search box. |
| Include inventory and worn gear | Items in your inventory and worn gear count in the bank value and are listed with the bank's stacks. They are read when you open or close the bank, or press Refresh. On by default. |
| Include Grand Exchange offers | Items in your Grand Exchange offers, and the coins committed to them or waiting to be collected, count in the bank value and are listed with the bank's stacks. They are read when you close the bank or press Refresh. On by default. |
| Use live prices | Every item that has traded in the last 24 hours gets a figure from the wiki's live traded prices, solid or soft with a word under the percentage when it is soft; items with no trades keep the guide price. On by default. |
| Up colour | The colour of a rise, on both tabs: the figures, the rows, the card's edge and the chart. Green by default. |
| Down colour | The colour of a fall, on both tabs. Red by default. |
| Single chart colour | Draws the Net Worth History chart in one colour instead of the up and down colours. On by default. |
| Chart colour | The chart's colour while *Single chart colour* is on. The 2h logo's gold by default. |
| Hide amounts | Hides every gp amount and item quantity in the sidebar - the bank value, every gain or loss in gp, each item's stack value and count (its picture too), and the Net Worth History's totals - keeping item names, pictures and every percentage. The eye beside the Discord icon is the switch. Off by default; remembered. |

Most of them are also controls in the panel's own **Options** menu, behind the settings icon in the card's
top-right corner: the three that decide what the card draws, a line, and the *Up colour* and *Down colour* swatches,
then *Colour presets* (see below), then *Use live prices* and the four that decide what the card counts and which stacks
get a row, then *Preset price ranges* as three boxes, then a *Net worth chart* section with
*Single chart colour* and, if you have hidden days, *Include days before v1.0.9* (or *v1.1.1*, whichever applies to you). A swatch opens
RuneLite's colour picker beside the sidebar, and the panel changes as you drag. *Reset to default* puts the preset
ranges and the colours back (not Slot 1), and *OK* closes the menu. *Show preset price ranges* has no entry of its own: the band button in the sidebar is
the switch, and this row is where it reads back. The sidebar opens on the tab you used last.

### Colour presets

Under *Down colour*, in the Options menu and in the *List options* menu beside the search box, each preset is one
click on its row, anywhere on it, and sets both colours at once:

- *Classic* - the green and red the sidebar ships with.
- *2h* - a bright green and a light blue.
- *Colour-blind* - orange for a rise and blue for a fall.
- *Slot 1* - a pair of your own. It starts as Classic. Pick an up and a down colour, then press *Save current colours
  to Slot 1* under it to keep them; press *Slot 1* any time to load them again. *Reset to default* leaves Slot 1
  alone.

A tick stands on the preset whose colours are the ones in use, and on none if you picked colours no preset has. It
follows every change: a preset, the colour picker as you drag, Reset to default. Both menus show the same tick.

## Where its files live

Everything is under `~/.runelite/plugin-data/bank-portfolio-tracker/`:

| File | What it holds |
|---|---|
| `bank-<accountHash>-<profileType>.json` | the last bank seen, one file per account and profile |
| `history-<accountHash>-<profileType>.json` | your bank net worth, one reading a day, one file per account and profile |
| `mapping.json` | the item id -> wiki name table (refreshed weekly) |
| `revindex.json` | the guide page's revision history (refreshed every six hours) |
| `baseline-D1.json` … `baseline-D180.json` | one guide table per window |
| `traded-latest.json` | the newest live traded snapshot (only while *Use live prices* is on) |
| `traded-H1.json` | the last closed hour's traded averages, fetched once an hour while the panel is on screen (only while *Use live prices* is on); asked for again a few minutes later, up to twice, while the wiki has not cut the hour yet |
| `traded-D1.json` … `traded-D180.json` | one day's traded averages per window (only while *Use live prices* is on) |
| `traded-D2.json` | yesterday's traded averages, kept as the day before's when the day rolls over at midnight UTC - no request (only while *Use live prices* is on) |

If you ran an earlier build, these files sat in `~/.runelite/bank-portfolio-tracker/`; RuneLite moves that folder
to the new place for you the first time the plugin runs, and nothing is lost.

Settings live in RuneLite's config group `bankpricemovement`. **Nothing about your bank ever leaves the
machine**: the only outbound requests are the wiki GETs above - and, while *Use live prices* is on, the wiki's own
whole-game traded endpoints (the latest prices, each day's averages and the last hour's), which are asked for every
item in the game at once rather than for yours. None of
them carries anything about you or what you own.

## Getting started

Open your bank once so the plugin can read it - until then the panel says *"Open your bank once to load your
items"*. After that the list works anywhere, logged in or not, including at the Grand Exchange; the card's
footnote says how old the reading is, and the bank is remembered per account across restarts, so the list is
there the moment you open the sidebar.

## Caveats worth knowing

- The guide price moves at Jagex's pace, several times a day, and an item with no trades stays on it: for
  those rows, refreshing more often than that changes nothing.
  The line at the foot of the card says why when you hover it, along with the time
  the prices on screen were last checked. The guide price is the one shown on the Grand Exchange website, so
  **the plugin agrees with that site only with *Use live prices* off**. RuneLite's own item hover uses the
  wiki's traded price by default, which differs most on thinly traded items - so a guide row here can sit a long
  way from that hover on something rarely traded, and a live row will usually sit close to it. Jagex moves a
  guide price by at most about 5% a day, so a large move shows over several days.
- A percentage here can differ from the GE site's by a tenth: the site prints whole percents of the same
  truncated figure.
- An item with no baseline on the chosen day shows "-" and sorts last under every ordering. With *Use live prices*
  on, an item with nothing to measure says `no trades` under its dash.
- A soft figure is a real measurement from thin trading, not a guess, but it can swing on a few trades: the word
  under its percentage is there so you can see that at a glance.
- Bank value counts every stack that has a price, plus your coins and platinum tokens unless you switch
  them off. Untradeables RuneLite can take apart are always in it, at what their parts are worth, which is what
  anyone would pay you for those parts. The ones it cannot are in it only when you ask for them, and then at
  their High Alchemy value, which is not what anyone would pay you for them. Whenever the total looks short, the
  stacks reading "-" in the list are the ones it is leaving out.
- Your Grand Exchange offers count while *Include Grand Exchange offers* is on: unsold items, bought items waiting
  to be collected, and the coins committed to or waiting in an offer. They are read when the bank closes and on
  Refresh, not the moment an offer changes.

## Something wrong?

Tell us in the 2hBuilds Discord - the mark beside the settings icon opens it. Three things answer most questions:

- **Which version you run.** The settings menu's header shows it.
- **What the sidebar shows.** A screenshot of the card and the list. When prices could not be fetched, the card's
  status line says so, and its hover says why.
- **The plugin's log lines.** RuneLite keeps one log for the whole client. To find it, right-click the camera icon
  at the top of the RuneLite window, click *Open screenshot folder...*, go up one folder and open `logs`. The file
  is `client.log` (Windows may show it as just `client`). Search it for `bank-portfolio-tracker` and for
  `bankpricemovement`, and paste the lines from the day it went wrong. Nothing in them can log anyone in, but they
  can show your computer's user name, so send them in a direct message.

## Licence

BSD 2-Clause. See `LICENSE`.
