# Living capitals

**Status: in progress.** Milestones 0 and 1 done and watched in game on 20 October 2026; milestone 2 (private stores) built and reworked on
23 October, not yet watched; 3 to 5 not started, in that order; what they measured and fixed is in [living-capitals-findings.md](living-capitals-findings.md). The stores have a plan of their own,
[bot-stores-plan.md](bot-stores-plan.md).

What is asked: capitals that feel lived in, with people who **stay and are seen**, some of them keeping a private store while the broker is not done.

## Decisions (taken 20 October)

1. **Where store goods come from**: stock generated at the keeper's level, capped per keeper and per day; what bots really loot can be added once
   field bots carry their good drops home.
2. **Prices**: as written in [bot-stores-plan.md](bot-stores-plan.md), then moved by sales.
3. **Bots buy from players' stores**: decided on 20 October, dropped on 23 October with the day's rhythm. Should it come back, with the broker:
   `BrokerService.getAveragePrice` **divides by zero** when the broker holds no listing (`counter = 0`).

## Milestones

0. **Measure and explain. Done.** Sanctum's missing half (the centre bridges read as no-walk volumes), its rides (a boat and a lift, `RideLinks`),
   the fruitless flask trips, and the crowd test: about 40 characters in sight keep 40 fps. Open: the Protectors Hall floor is missing from the
   mesh, so its terrace stays empty.
1. **A capital day. Done.** `TownRoutine`: spells of 5 minutes (home 20 %, sitting 10 %, an errand to a town service 35 %, a visit 35 %; until
   23 October 30, 15, 30, 25, too still once the stores held 44 keepers), the same every day, starting
   at each resident's own moment. Watched: no FARMING in a capital, 5 to 17 of Sanctum's 117 residents moving at any moment.
2. **Private stores. Built 20 October**, see [bot-stores-plan.md](bot-stores-plan.md).
   - Eight in ten residents of level 20 and over keep a store, 44 at a time on a rota that goes round once a day, at a market stall, renewed about
     every hour.
   - Themed stock generated at their level, priced from the template, the quality and the keeper's temperament, cheaper the longer it is unsold.
   - Check: a player buys from a bot in game, the kinah moves, and EXCHANGE_LOG has the line.
3. **Visitors.** Agreed on 23 October. Capital residents are made at the level of the map's monsters, as everywhere, so a capital holds mostly
   residents under 30 and from 45 to 49 (Pandaemonium on 23 October: 18 of 135 at 50 and over, 5 from 30 to 44). A tester saw no high levels in
   town ([feedback-2026-10-21.md](feedback-2026-10-21.md)).
   - Sleepers of the faction, from 10 to the highest level in the pool, are woken at the capital's obelisk. They spend 20 to 40 minutes on the
     town routine, then fall asleep out of sight. Their home never moves: `goHome` puts them back in their region on their next spawn.
   - Levels are drawn to fill what the residents lack, so the whole crowd spreads from 10 to the top, rather than drawn in the pool's proportions.
   - 10 to 20 visitors at a time per capital, in rotation.
   - A visitor flag, so that no rule moves a home into town. No store at first; opening some would bring stock of 50 and over.
   - Only sleepers: no region loses a bot that is awake, and a visitor is not counted in its own region while it is away.
4. **Speech.**
   - Store adverts in the region channel ("Selling plate 30-35, see my store"), one every few minutes per capital, not per bot.
   - Calls for a group, echoing the offers `BotRecruiter` fills.
   - Cheap and highly visible, noisy if overdone: a cap per channel.
5. **Ambience.**
   - Groups of two to four facing one another, with emotes, first.
   - A rare wave or bow when a player stops in front of a bot.
   - Bots duelling on a square: duels exist, and onlookers are life.
   - Pets, so a crowd is not all combat gear (costumes dropped on 23 October).

The detailed plan for milestones 3 to 5 is [capital-life-plan.md](capital-life-plan.md).

Dropped on 23 October: **bots as buyers** of players' stores and the broker, and **the day's rhythm** (fuller in the evening, arrivals through the
teleporter and the obelisk).

## Later, to be decided

- **A companion keeps a store for its owner** (`//bot shop`): the absent merchant of official servers. It sells the owner's goods, never its own, at the
  owner's prices. The one exception to "an owned bot never sells" ([bot-gear.md](bot-gear.md)).
- **Crafters at the tables**, with the animation. Real crafting would stock the stores; it belongs to the economy phase.
- **A legion recruiting on the square**, once legions exist.
- **The boat's timetable**: the boat is the client's alone, so a rider vanishes on boarding rather than sailing with it.

## Risks

- **Memory and the client.** 150 bots a capital is already the cap. A keeper costs almost no ticks but still a character in sight, so the client
  ceiling decides.
- **Economy.** Generated stock creates items, and sales move kinah. Everything goes through the engine's own paths, so EXCHANGE_LOG traces it, and
  the stock cap per keeper is the valve.
- **A frozen store.** If a bot keeps a store, everything that moves it (rescue, director, sleep, group invitation) must close the store first.
  Otherwise `PlayerRestrictions` refuses the move without saying why.
