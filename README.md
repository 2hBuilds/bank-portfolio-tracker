# 2h Bank Portfolio Tracker

Your whole bank, priced, with what each item's Grand Exchange price has done over the last day, week, month,
quarter or half year - live traded prices for the items the market is actually trading, the daily guide price
for the rest, and your inventory and worn gear counted in.

## What the sidebar shows

**The Bank value card**, at the top, is your whole bank as one figure:

- the **total** - every stack that has a price, `unit price x quantity`, summed, **plus your coins and
  platinum tokens** (1,000 gp each), and by default what you are carrying and wearing too (see below). With
  *Include inventory and worn gear* and *Use live prices* both off, it matches the figure RuneLite's own Bank
  plugin puts in the bank window's title bar whenever every stack has a guide price (an untradeable or unpriced
  stack is counted by neither);
- the **gp move** and the **percentage** for the window you have lit, computed over the stacks that had a price
  on *both* days, so the two ends of the comparison are the same basket;
- the **window chips** `1d | 7d | 30d | 90d | 180d` - click one and the whole panel follows it;
- a **provenance footnote** ("1d vs 08 Sep - bank 09:00") saying which day the figures are measured against and
  when the bank was last read;
- **hover the card** - once you have turned on *Show hover text*, see below - and you get one thing: the total
  written out to the last gp, commas and all - *"446,901,681 gp"* - which is the one figure the rounding on the
  card takes away. Everything else is already on the card or one chip away, so the hover does not repeat it;
- a last line reading **"Item prices update every 24hrs"**, because Jagex publishes the guide price once a day
  and that is how often any of these figures can move - with *Show hover text* on, hover it for why, and for
  when the plugin last checked;
- a **Refresh** link, and one click refreshes everything: **your items at once** - your bank as it is now if it
  is open (see *While you bank*, below), and what you are carrying and wearing - and **the prices in the
  background**. The prices are re-checked at most once every 30 seconds, so a click sooner than that updates only
  your items: with the bank open it does so quietly, and with the bank closed a line under the controls says
  *"Refreshed n s ago - wait"* - that line, like any other problem, prints there rather than interrupting
  anything. Guide prices only move once a day, so a refresh usually brings the same figures back - with *Show
  hover text* on, hovering the line above tells you when the prices on screen were last read, so you can tell
  "nothing changed" from "nothing happened". A click is answered in two words: *Refreshing...*, then **Up to
  date**, which fades back to *Refresh* on its own a minute later;
- a small **gear**, just under the Refresh link, which opens the panel's **Options** menu: *Refresh prices
  now* (the prices alone, whether your bank is open or not), the three switches for the card's own figures,
  the four that decide what the panel counts and which
  stacks get a row (see *Settings* - they are the same switches as RuneLite's settings page, so either place
  works),
  then the three **Preset price ranges** boxes, and last in the list **Show hover text**, with a small box
  beside it - empty when it is off, ticked when it is on. Along the bottom sit two buttons: **Reset to
  default** on the left, which puts *100k / 1m / 10m* back into the preset boxes, and **OK** on the right,
  which closes the menu - it takes the boxes with it on the way out, which closing the menu does anyway, since
  everything in here saves itself as you set it. Clicking the gear again while the menu is open closes it too:
  the gear is a switch, not just a way in.

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

**The gp figures on the row are rounded** the way every other figure here is: a change of 3,432 gp reads `+3.4k`.
Click the row for the exact number.

- **click the row and it opens.** The row grows, and the detail appears underneath it, inside the same card, as
  four labelled lines:

  ```
  Worth now    4,618 gp each
  Was          4,190 gp  (19 Sep)
  You have     7  =  32,326 gp
  Change 1d    +428 each  +10.2%
  ```

  **Change** always names the window you are on - *Change 1d*, *Change 7d*, *Change 30d* - so the figure says
  what it is measuring without you looking back up at the chips. Under those four sit only the lines that have
  something to add: that the item is untradeable and priced at its parts or its alch value, where the stack is
  split between your bank, your inventory and what you are wearing, whether the price is live and how many
  traded yesterday, or which test a live price failed. An untradeable held at its alch value has no *Was* and no
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
  *Preset price ranges*, at the foot of the Options menu (the gear, under the Refresh link), carries a box for
  each, and typing a new amount into one re-cuts that chip - so a big bank can read *1m+ / 10m+ / 100m+*. *Reset
  to default*, bottom left of that menu, puts *100k / 1m / 10m* back.
- on the **right**, a **sort button** naming the column the list is ordered on, with a small **arrow** for the
  direction: down for biggest first, up for smallest. Click it for the four columns - *Percent change*, *gp
  change*, *Item price*, *Stack price* - and click the lit one again to flip it; a column you have just picked
  always starts biggest first. *Item price* is what one of the item costs; *Stack price* is what the whole stack
  is worth (price x quantity), so a hundred robin hood hats climb above one item that costs more each. *gp
  change* is what the whole **stack** made or lost - the figure on the row's second line - so a big pile of a
  small mover beats a single item that moved further. Every one of those figures is printed on the row, so
  whichever you pick, the list agrees with something you can read. Rows with nothing to sort on come last in
  either direction.

Rows come in pages of 250 with a "Show *n* more" button under them, so an 800-item bank does not freeze the
sidebar. If a band matches nothing, the panel says so and offers *Clear price range* in one click.

## While you bank

A quick gear swap should cost you nothing, so the panel keeps still while your bank is open:

- **The list does not redraw while your bank is open.** Deposit and withdraw as much as you like - nothing is
  re-read and nothing is rebuilt until you are done. Anything you click in the panel itself - a window chip, the
  sort button, a price band - still answers at once.
- **A thin green ring breathes round the Refresh link** when your bank, or what you are carrying or wearing, has
  changed since the list last read it - a sign the list is behind. It breathes from faint to full over three seconds and
  back out over three, again and again until the list catches up, and it only shows while the panel is on screen.
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

**Hover text is off until you ask for it.** Turn on **Show hover text** - last in the Options menu, under the
gear, in the row above *OK* - and the panel starts explaining itself when you rest the pointer on something: the
bank value gives you the exact total to the last gp, and the sort button, the band button, the Refresh link, the
*"Item prices update every 24hrs"* line and every item in the Options menu say what they do. The Refresh link's
hover reads *"Re-read your items and re-check the prices. Jagex publishes guide prices once a day."* - the same
wherever you are, because a click does the same thing everywhere. Leave the switch off and nothing opens
anywhere. It is off when you install the plugin, and it remembers whichever way you set it.

**An item row never opens a hover, at either setting.** Its detail is not a tooltip - you **click the row** and it
opens on the page, under the row's own line, and stays there until you close it. That is also where you read an
item's name when it is too long to fit on the row. So this switch changes nothing about the rows: click one and it
opens whether the hover text is on or off.

The rows you leave open are remembered while you use the panel.

## Where the numbers come from

**"Now" is the Jagex GUIDE price** - the number the in-game Grand Exchange, the GE web site and RuneLite's own
tooltips show. It comes out of RuneLite's price table, which the client already keeps up to date, so it costs
no request at all.

**"Then" is the same table on an earlier CALENDAR DAY.** The OSRS wiki republishes Jagex's guide prices as the
page `Module:GEPrices/data.json`, roughly once a day; the plugin reads the revision history of that page once
(one request), picks the revision whose own `%LAST_UPDATE%` stamp falls on the day the window asks for, and
fetches the tables it needs in a single batched request. That is about **two requests a day whatever your bank
holds** - there is no per-item lookup, ever. Item names are joined to item ids through the wiki's
`prices.runescape.wiki/api/v1/osrs/mapping` table, fetched at most weekly.

**Percentages truncate toward zero**, exactly as the GE site's do, and take their sign from the gp change: a
fall too small to survive the truncation still reads `-0.0%` in red, as the site prints it. The site shows whole
percents and this shows one decimal of the same truncation, so "-3%" there is anything from "-3.0%" to "-3.9%"
here - never a bigger number, and never a different sign. (A row's face drops the decimal from 100 % up, as
described under the row; the truncation and the sign rule are the same.)

**What is left out of the LIST:** coins, platinum tokens, bank placeholders, bank fillers and anything the
Grand Exchange does not list. Noted stacks fold onto the item they note, and duplicate stacks of one item are
summed. Coins and platinum tokens get no row because their price never moves - but they *are* counted in the
Bank value card, at face value and 1,000 gp each, because they are part of what your bank is worth. That is
also why a cash-heavy bank shows a smaller percentage than its items do: the cash sits in the denominator and
does not move. If you would rather read your bank as the items alone, turn off *Include coins and platinum
tokens* in the Options menu.

**Untradeable items** - graceful, void, barrows gloves, your fire cape - have no guide price at all, so they
are left out by default. Turn on *Include untradeable items* and each one gets a row at its **High Alchemy**
value, tagged *alch* where the movement figures would be, and is counted in the Bank value. An untradeable
RuneLite can take apart is priced at what its **tradeable parts** are worth instead - crystal armour at its
crystal armour seeds (three of them for a body), a slayer helmet at its black mask, a Bow of Faerdhinen at its
inactive form - and such a row carries a real gp and percentage move, because the part it is made of has a
guide price that moves. The ones left at an alch value never appear in a percentage or a gp move: there is no
earlier price to compare an alch value with.

**What you are carrying counts too.** *Include inventory and worn gear* is **on by default**: the items in your
inventory and the gear you are wearing are valued and listed exactly like the bank's own stacks, by the same
rules - noted stacks fold onto the item they note, coins and platinum tokens in hand are worth face value and
1,000 gp each, and an untradeable you are wearing follows the untradeables switch like any other.

They are read together with your bank - **when you open it or close it, and when you press Refresh** - and at no
other time. Nothing is watched in between - eat a shark with the bank closed and the row sits still until one of
those happens.
That is deliberate: reading two containers on every inventory change would be work on the game's own thread for
a number that moves back a second later.

An item you hold in **both** places is **one row** with the quantities added together, and clicking the row
names the split under its figures: *"3 in bank, 1 in inventory, 1 worn"* (a
worn-only item just says *"1 worn"*). The Bank value card counts the lot.

One consequence worth knowing: **RuneLite's own bank title bar will read lower than this card**, by roughly what
you are carrying and wearing, because it counts the bank container and nothing else. Turn the switch off and the
two agree again - every figure here is then the bank alone.

If the wiki cannot be reached, the rows keep their prices - they are RuneLite's, not the wiki's - and lose only
their movement; the status line says so in grey, and nothing is red about it.

## Live prices

The guide price above is Jagex's, and Jagex publishes it **once a day**. That is the right number for most of a
bank, but it means a Refresh usually changes nothing - and for the handful of items that really are being
bought and sold all day, it is a day out of date. So there is a switch, **Use live prices**, and it is **on by
default**.

With it on, an item that is *actively traded* is priced from the wiki's live traded series instead: the unit
price you see is the midpoint of what people are paying and asking right now, the 1d figure compares it against
that item's traded average for the day before, and a Refresh really does move it. Every window works the same
way - **each one compares against that many calendar days before today**, so 1d is yesterday, 7d is a week ago
to the day, and clicking the row names the day it used. Everything else in your bank
carries on exactly as it did.

**"Actively traded" is five tests, and an item has to pass all of them:**

- **At least 100 of it changed hands yesterday.** Below that there is not enough trade for a price to mean
  anything - one person selling one item at a silly number *is* the whole day's market.
- **The buy and sell prices are within 10 % of each other.** A wide gap means nobody agrees what the thing is
  worth, and the midpoint between them is a guess rather than a price.
- **The live price is within 50 % of the guide price.** This is the backstop: anything further out is a
  manipulation, a mistake or a dead market, and the guide price is the safer number.
- **Yesterday's buying and selling were within 10 % of each other too.** The same test, one day back, on the
  day the 1d figure is measured against. A tinderbox bought at 37 gp and sold at 12 gp all day has a daily
  "average" struck between two numbers a hundred percent apart: there is no yesterday's price there to compare
  today with, whatever today looks like.
- **The live price is within 50 % of yesterday's average.** An item that has apparently trebled since
  yesterday almost certainly has not - it is a thin market, a coincidence of who happened to trade, or
  somebody pushing a 1 gp item to 2 gp. This is your own rule about ignoring live changes over 50 %, applied
  to yesterday as well as to the guide.

Anything that fails a test keeps the daily guide price, and clicking the row says which test
it failed first - *"Guide price - live not used: 12 traded yesterday"*, or *"buy/sell gap 100 % yesterday"*,
or *"live price 181 % from yesterday's average"*. Nothing is hidden and nothing is estimated; every row is one
series or the other, and the row tells you which.

This matters more than it sounds. On a real 500-stack bank, pricing *everything* live moved 87 rows by over
20 % on the 1d window against 1 row under the guide price - and almost all of those 87 were junk nobody buys,
where a single odd trade is the entire day's evidence. The five tests are what keep those out while letting
the items you actually watch move in real time. The last two were added after the first three let a tinderbox
read **+181 %**: it really was traded, hundreds of times, and the buy and sell prices really were close
together today - yesterday's were 12 gp and 37 gp, which is not a price to measure anything against. On that
bank the two extra tests sent about seventy stacks back to the daily guide price, and every one of them reads
about 0 % there.

**Turn it off** (the gear menu, or RuneLite's settings) and the plugin is exactly the guide-price plugin it was
before: one series for everything, no traded requests made at all, and every figure matching the Grand Exchange
website. That last point is the reason to turn it off - **the GE website shows the guide price, so the two only
agree with this switch off**. With it on, the line at the foot of the card says so: *"Live prices on - thin
items daily"*.

## Settings

Fifteen items, and every one of them is also a control in the sidebar: change it in either place and the other
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
| Include untradeable items | List untradeable stacks at their tradeable parts' value, or else their High Alchemy value, and count them in the bank value. |
| Include inventory and worn gear | Items in your inventory and worn gear count in the bank value and are listed with the bank's stacks. They are read when you open or close the bank, or press Refresh. On by default. |
| Show hover text | The bank value and the panel's controls explain themselves when you rest the pointer on them. Off by default. Item rows never use hover text either way - click a row to open its detail. |
| Use live prices | Actively traded items use the wiki's live traded prices for every figure; thin items keep the daily guide price. On by default. |

Eight of them are also switches in the panel's own **Options** menu, under the gear beneath the Refresh link -
the three that decide what the card draws, then *Use live prices* and the three that decide what the card counts
and which stacks get a row, and last of all *Show hover text*. *Preset price ranges* is in that menu too, as three
boxes, with *Refresh prices now* at its top and a *Reset to default* and an *OK* button along the bottom.
*Show preset price ranges* has no entry of its own: the band button in the sidebar is the switch, and this row
is where it reads back.

## Where its files live

Everything is under `~/.runelite/plugin-data/bank-portfolio-tracker/`:

| File | What it holds |
|---|---|
| `bank-<accountHash>-<profileType>.json` | the last bank seen, one file per account and profile |
| `mapping.json` | the item id -> wiki name table (refreshed weekly) |
| `revindex.json` | the guide page's revision history (refreshed every six hours) |
| `baseline-D1.json` … `baseline-D180.json` | one guide table per window |
| `traded-latest.json` | the newest live traded snapshot (only while *Use live prices* is on) |
| `traded-D1.json` … `traded-D180.json` | one day's traded averages per window (only while *Use live prices* is on) |

If you ran an earlier build, these files sat in `~/.runelite/bank-portfolio-tracker/`; RuneLite moves that folder
to the new place for you the first time the plugin runs, and nothing is lost.

Settings live in RuneLite's config group `bankpricemovement`. **Nothing about your bank ever leaves the
machine**: the only outbound requests are the wiki GETs above - and, while *Use live prices* is on, the wiki's own
whole-game traded endpoints, which are asked for every item in the game at once rather than for yours. None of
them carries anything about you or what you own.

## Getting started

Open your bank once so the plugin can read it - until then the panel says *"Open your bank once to load your
items"*. After that the list works anywhere, logged in or not, including at the Grand Exchange; the card's
footnote says how old the reading is, and the bank is remembered per account across restarts, so the list is
there the moment you open the sidebar.

## Caveats worth knowing

- The guide price moves at Jagex's pace, roughly once a day, and everything *Use live prices* leaves on it is a
  day-over-day comparison rather than a ticker: refreshing more often than that changes nothing for those rows.
  The line at the foot of the card says why when you hover it - with *Show hover text* on - along with the time
  the prices on screen were last checked. The guide price is the one shown on the Grand Exchange website, so
  **the plugin agrees with that site only with *Use live prices* off**. RuneLite's own item hover uses the wiki's traded price by default,
  which differs most on thinly traded items - so a guide row here can sit a long way from that hover on
  something rarely traded, and a live row will usually sit close to it. Jagex moves a guide price by at most
  about 5% a day, so a large move shows over several days.
- A percentage here can differ from the GE site's by a tenth: the site prints whole percents of the same
  truncated figure.
- An item with no baseline on the chosen day shows "-" and sorts last under every ordering.
- Bank value counts every stack that has a price, plus your coins and platinum tokens unless you switch
  them off. Untradeable items are not in it until you ask for them, and even then the ones RuneLite cannot take
  apart are counted at their High Alchemy value, which is not what anyone would pay you for them. The ones it
  can are counted at their parts' guide price, which is. Whenever the total looks short, the stacks reading "-"
  in the list are the ones it is leaving out.

## Licence

BSD 2-Clause. See `LICENSE`.
