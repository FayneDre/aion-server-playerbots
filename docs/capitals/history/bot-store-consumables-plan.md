# Bot stores: food, scrolls, higher stones and more lines

Status: done: validated in game by the user on 1 November. Steps 2 to 4 and the whole page are in. Not done: step 1, a census report script over the opening log lines. The stones answer (2) stands on a default until the user gives one.

Written on 31 October 2026 from the tester's feedback of 29 October (a player's remarks on what live private stores sell, quoted in French in the feedback file):

1. **Food and scrolls** (running, attack speed, and so on) matter more than gear to the players who buy; hardly anyone sells gear, **low-level gear (under 50) above all**.
2. **Manastones**: keep one or two stores selling stones of every level (20 to 70) and let **all the others sell only 60 and 70**.
3. Nobody has seen a bot sell the **All-Powerful stone, serums or upgrade stones** yet.
4. A quality of life one: let bots sell the **30 minute or 1 hour scrolls**, which only exist through events (the craftable ones are capped at 5 minutes).
5. Look into raising **the number of lines a store can sell** (today 10).

What a store holds today is in [../how-it-works.md](../how-it-works.md) and [../history/bot-store-goods-plan.md](../history/bot-store-goods-plan.md).

## What the code does today (`BotStoreStock`, `BotStoreGoods`, `BotStores`)

- A keeper has **one theme, fixed by its id**: a third are opportunists (`MIXED`, a little of everything), then of the rest a quarter keep coins, and the others share gear (for one class),
  accessories and stones evenly. In a market that is about **one store in three an opportunist, one in six each for gear, jewels, stones and coins**. Nothing sells food or scrolls.
- A store holds **8 lines at most when it opens** (`STOCKED_LINES`), 10 being **the engine's limit**, written as `store.getSoldItems().size() == 10` in `PrivateStoreService.validateItem`
 . Stock is generated, never looted: only what a player could come by, **tradeable and obtainable** (`isTradeable`, `BotGearFit.isObtainable`).
- **Gear** is drawn from the keeper's level less 20 to plus 5, blue or better; nothing keeps the gear of the starting levels off the shelves. The tester's remark is that hardly anyone sells it, so a market full of it reads as unlike a live one.
- **Stones**: a stones store sells the upgrade items and manastones from level 10 up to its keeper's level plus 10 (`Pierres 10-N`), every stones store alike. The tester wants most of them to sell 60 and 70.
- **The rare goods** the tester has not seen exist: the All-Powerful stone (data: Omega), the evolution stone and the holy upgrade serum are in `BotStoreGoods.UPGRADES`, **flagged rare, stocked by a keeper
  of level 60 or more, and only by about one stocking in ten**. Of a market's stores, roughly a sixth are stones stores (plus an opportunist's share), a tenth of them draw rare goods and few
  keepers are 60: well under one store in a hundred ever holds one. That is why none was seen, not a fault. The five enchantment stones (L1 to L5, from keeper level 20 to 55) are not rare and
  a stones store draws them four lines in ten (`STONE_WEIGHTS`), so they should be seen; whether they are is one of the things the census below asks.

## What the data holds that a store could sell

Read off `item_templates.xml` (counts are of templates, the Elyos and the Asmodian copies counted apart):

- **Scrolls that change a stat** (`cName` `scroll_speed_*`): Courage (attack speed), Running, Raging Wind (flight speed) and Awakening (casting speed), each Lesser (level 10), plain (20) and Greater (30),
  12 tradeable items (ids 164000071 to 164000079 and 164000132 to 164000134), and the Movement Speed Scroll (164000033). The alchemy scrolls (`scroll_alchemy_*`: Lesser Physical Defense, Accuracy,
  Evasion, Physical Attack, and the like) are about 6 families by size. These are the "craftable" ones the tester says last 5 minutes.
- **The 30 minute and 1 hour scrolls** are **event items**: `[Event] Lesser Running Scroll` (164002003), `[Event] Lesser Courage Scroll` (164002002) and the rest of `event_scroll_speed_*` (`_30a` and up).
  Their mask is **not tradeable** (12360 against 12414 for the craftable ones), so the engine's own check (`!item.isTradeable()`: "cannot be exchanged") refuses them on a shelf. "The permission to bots"
  the tester asks for is a bypass of that check for bots and for those ids only.
- **Food** (`cName` `food_*`): about 600 tradeable templates, in families by what they raise: two, three or four stats (about 500), and one stat each (physical attack, movement speed, maximum HP, maximum
  MP, HP and MP regeneration, evasion, accuracy and magical accuracy, resistance), ten to twenty levels each. Where a family has levels, the keeper level picks the right ones.

## The plan

### 1. A census first (half a day, before any change)

A log line per store when it opens (theme, keeper level, the template of each line), and a report script over a day's log, written like `tools/measure.sh`: how many stores of each theme, the share that ever held an
enchantment stone, a rare good, a stone over 50, gear under 50. The tester's four observations become four figures, before and after, which is the rule of this project.

### 2. Consumables as themes (about two days)

Two new themes, `FOOD` and `SCROLLS`, in `BotStoreStock.Theme`, and the shares of `themeOf` moved to **gear a tenth, jewels a tenth, stones a sixth, coins a tenth, food a fifth, scrolls a sixth, opportunists a sixth** (the
exact shares are decision 1). Keeping the old shares for the keepers a restart finds, as `themeOf` does when it adds a theme, so no shelf is stranded.

- **Scrolls**: the families above by keeper level (a keeper of 40 sells Lesser to Greater, one of 30 the two lower), several stacks of 20 to 100 at prices from the data (`template.getPrice()` is 5 on some of
  them: they are priced by `BotStoreGoods` ranges, a guess at 1 000 to 50 000 kinah a stack to be read off the tester's prices).
- **Food**: by keeper level and by family: a store of "food for damage" and one of "food for survival", ten lines each, with a few lines of the plain families in an opportunist's shelf.
- **Event scrolls**: a **whitelist** (`BotStoreGoods.EVENT_SCROLLS`, the ids above) that `PrivateStoreService.validateItem` lets through **for a bot owner only** (a three line change in an upstream file, which
  the merge plan records as a core patch); sold at the price of the 5 minute scroll times 6 and 12. Nothing about real players' stores changes.
- The alchemy scrolls, the faction "danger" scrolls (`scroll_l_speed_*`, `scroll_d_speed_*`) and the Coliseum training ones stay out: they are not what the tester named.

### 3. Mana stones and the rare goods (about a day)

- **A wide stones store is the exception**: of the stones stores of a map, one or two (chosen by keeper id, the same every day) keep today's wide range (10 to level + 10), **the others sell only stones of 60 and
  70** when their keeper's level admits it (level 50 and over); a keeper under 50 keeps the wide range, since there is no 60 to sell. Decision 2 gives the number.
- **The rare goods get seen**: a stones keeper of level 60 or more draws them with probability one in three and not one in ten, with a guarantee that at least one stones store of a market holds one at
  any hour, so that a tester who looks finds the All-Powerful stone, the serum or the evolution stone. Their prices stay the guide's.

### 4. Less low-level gear (half a day)

A gear store keeper under level 50 becomes an opportunist (or sells food), and the gear of a dedicated store starts at level 50: `DEDICATED_GEAR_UNDER` reads the keeper's level but not below 50. Decision 3.

### 5. More lines (a day, with the tester's client)

- The limit is one number in the engine: `PrivateStoreService.validateItem` refuses the eleventh line. It becomes `gameserver.playerbot.store.lines` (default 10) **for a bot owner**, 10 as ever for a player;
  `BotStores.MAX_LINES` and `STOCKED_LINES` read it too. A core patch of about four lines.
- What the **client** does past ten is not in the data: the seller's window has ten slots, but a bot never uses it, and the buyer's window is filled from `SM_PRIVATE_STORE`, which writes the count it is given. Which
  of a clipped window, a scrolling list and a client that closes is unknown, so the first step is **a test store of 12, 15 and 20 lines** opened by an admin command on a bot, and the tester opening it. Only a count
  the client shows whole is kept; if none does, the limit stays at 10 and the answer is the one the tester has to hear.
- Another way to sell more with ten lines: bigger stacks, or **two stores a keeper** (not possible: one store a character).

### Order and checks

| # | What | Checked by |
|---|------|------------|
| 1 | Census log and report | The tester's four observations as four figures |
| 2 | Food and scroll themes, event scrolls, the bot-only bypass | A test that every line of every theme is tradeable or whitelisted and priced; a day of the census; the tester in a market |
| 3 | Wide stones store as the exception; rare goods seen | The census: stores holding a stone of 60 or 70, a rare good |
| 4 | Gear from level 50 | The census: gear under 50 |
| 5 | A test store of 12, 15 and 20 lines | The tester's client |

About five days of work. The prices of food and of event scrolls are a guess until the tester has priced them against what he sees on live servers.

## Decisions taken (31 October)

1. **Shares**: as proposed, food a fifth, scrolls a sixth, gear and jewels a tenth each.
2. **Stones**: not understood by the user, so the default below stands until answered. Out of a map's stones stores, **two keep the wide range** (stones from 10 up to the keeper's level plus 10), chosen by keeper id so
   that they are the same every day, and **all the others sell only stones of 60 and 70**; a keeper under level 50 cannot stock a 60, so it sells no stones at all (it takes gear, food or scrolls instead, unless it is one of the two wide ones).
3. **Gear under 50: there must be some.** Step 4 changes: no ban, only a smaller share. Gear stores of keepers under 50 stay, at a tenth of the stores of such keepers and not a sixth.
4. **Event scrolls**: the user does not know; defaults: six times the price of the 5 minute scroll for 30 minutes and twelve times for 1 hour, in stacks of 5 to 20, two lines of them a store at most, in food and scrolls stores only.
5. **A complete sale page**: stores open with **10 lines, a full page** (`STOCKED_LINES` 8 to 10), the test above 10 is dropped and the limit stays what the engine says: the core patch of step 5 is not needed.
   **For the evolution stone, the holy serum and the All-Powerful stone, a store must always be present**: at every hour, in every capital market, a keeper, of any level, holds all three,
   renewed with its shelf, and not by a draw.

## The decisions that were asked

1. **The shares**: food a fifth, scrolls a sixth, gear and jewels a tenth each, as above, or the other way: how much of a market should be consumables?
2. **How many stores keep the wide range of stones** per map (one or two): and whether a keeper under 50 may sell stones at all.
3. **Gear under 50**: no gear store of a keeper under 50, as proposed, or fewer but some.
4. **The event scrolls**: the whitelist is Courage, Running, Raging Wind and Awakening in the 30 minute and 1 hour forms; the price (a multiple of the 5 minute scroll's) and whether a bot may hold more than a few lines of them.
5. **A test store past ten lines** on the tester's client: if it shows them, how far to go.
