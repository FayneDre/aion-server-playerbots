# Plan: an auction house stocked by the server (AHBot model)

Status: not started. Analysed on 31 October from the engine's `BrokerService`; nothing is written. Asked by the tester: a broker with items for sale, on the model of AzerothCore's AHBot.

## What AHBot does, and what we keep

AHBot is a module that, on a timer, **lists items for sale in the auction house** so it is never empty, keeps their number near a target, prices them around a
market value, and optionally **buys** what players list. Its knobs: items wanted per house, share per item category and per quality, price multiplier
(min/max), stack sizes, level and item blacklist, run interval, buy chance. We keep the same shape: a **seller** that tops the broker up, a **price rule**,
an optional **buyer**, all driven by a property file and switched off by one property.

## What the engine offers (read in `BrokerService`)

- **One broker per race**, global, not per town: two maps (`elyosBrokerItems`, `asmodianBrokerItems`) plus the settled ones. So two houses to stock, not
  one per capital. Items are `BrokerItem(item, price, sellerId, splittingAvailable, race)`, expiry `gameserver.broker.registration_expiration_days` (8).
- **A seller must be a real `players` row**: table `broker` has `seller_id` with a foreign key on `players` and `ON DELETE CASCADE`. Bots already live on reserved accounts
  (`BOT_ACCOUNT_ID_BASE` and up), so a **house character per race** (never logged in) fits; deleting it would delete its listings.
- `registerItem` is a player's door: 15 listings at most, a fee of 2 to 4%, kinah and inventory taken from the seller. **The house needs a door of its own**, a small
  core patch in `BrokerService` like the one in `PrivateStoreService.validateItem`: `registerForHouse(race, sellerId, item, price, split)` without fee, limit or inventory.
- A sale goes through `buyBrokerItem`: the buyer pays, the listing becomes a **settled sold** entry waiting for the seller to collect. Nobody collects for the house:
  they pile up in memory and in the table. The house must **settle itself** (delete sold entries, return expired ones) in the same pass that restocks.
- Expired listings are returned to the seller's settled list (`checkExpiredItems`): for the house, the same pass deletes them and relists.
- `getAveragePrice` divides by the count of listings of an item and is guarded at 0 here; the note in `capitals/how-it-works.md` is about the bot buying path, still true.

## Reuse (do not rewrite)

`BotStoreGoods` (guide ids and the ranges a live broker sold them in), `BotManastones.rangeOf`, `BotStorePricing` (price from template and quality, falling with
hours unsold), `BotStoreStock` (templates a theme may hold, by level and class). The store census already says which goods are credible.

## Decisions to take (a recommendation each)

1. **House characters**: one per race, created once on a reserved account, named plainly (e.g. a trader's name). Recommendation: yes, **not** the pool bots (a pool bot logged out
   or deleted would take its listings with it, and the cap of 15 would bind).
2. **Size**: Recommendation: start at **600 listings a race**, configurable; memory measured before and after (each `BrokerItem` keeps an `Item`; the heap is 2560 MB with 1260 in use
   at 45 bots, to be re-measured at 1000+).
3. **Categories**: follow the client's own masks (`BrokerItemMask`: weapons, armour, accessories, manastones and enchantment, consumables, crafting and gathering materials, recipes,
   other). Recommendation: a share per mask in the property file, weighted toward what players actually buy (stones, consumables, materials) over gear.
4. **Quality and level**: a share per quality (common to legend) and a **level curve that follows the server's population**, not the item list: more at 20 to 55.
   Recommendation: the same candidate filters as the stores (tradeable, not quest, not a remnant).
5. **Prices**: base on the guide's ranges where there is one, otherwise `BotStorePricing`, then a random spread of about ±15% per listing (never a flat price). Recommendation:
   the house sells **at or a little above** the market, never far below, so it does not wreck what players sell; players selling under it win the sale.
6. **A buyer?** The tester decided on 23 October that bots never buy from the broker. AHBot's buyer exists to give sellers a floor. Recommendation: **leave it for a second step**, off by
   default, with a daily kinah ceiling and a rule to buy only a listing priced under the market median; a bot buyer is also a kinah faucet and must be capped.
7. **Both races, one rule**: the house of a race sells what the race may use (class and race restrictions already in the templates).

## Milestones

0. **Read the data**: how many tradeable templates there are per mask and quality; which have a price (guide or template) and which would be guessed. A short census, as for the stores.
1. **The door**: `BrokerService.registerForHouse`, house settling (`settleHouse`) and a test with a house character in a test database. Core patch kept to a few lines.
2. **The house characters**: created at first start on a reserved account, one per race, recorded so a restart finds them.
3. **The seller**: a timer (every 15 minutes, property) that counts the house's listings per mask and tops each up to its share, with the price rule and stack ranges; first fill spread over
   some minutes so startup is not slowed. Property `gameserver.playerbot.broker.enable` (false by default) and the targets.
4. **Settling and relisting**: sold entries deleted, expired ones returned and relisted, an item removed when its template is on the blacklist.
5. **Watch in game**: the tester opens the broker in both capitals: categories filled, prices believable, a purchase works, kinah and inventory right, `exchange.log` has the line.
6. **Measure**: heap before and after, tick cost of the pass, listings per category against the targets over a day; compare the broker's average prices with the guide's.
7. **The buyer** (optional), after the watch, if the tester wants it.

## What can go wrong

- **Memory**: thousands of `Item`s on a 2560 MB heap shared with 1000 bots. Mitigation: milestone 0 sizes it; the target is a property; measure at every step.
- **Prices out of line**: templates without a guide price get a guess. Mitigation: list them in the census and let the tester correct from the broker window.
- **Players undercut by the house**: the price rule keeps the house at or above the market.
- **The settled list grows** if settling is missing: a test counts the settled entries of a house after a sale and an expiry.
- **A deleted house character** deletes its listings (cascade): a guard in the roster refuses to delete them, like the one for the players' own characters.
- **Upstream merges**: the door is the only core change; the rest lives in `playerbot/economy/`.

## First step proposed

Milestone 0 (the census, read-only), then milestone 1 with its test. No server restart is needed for either.
