# Living capitals

**Status: in progress, milestone 2 (private stores), built and not yet watched in game.** Milestones 0 and 1 are done and were watched in game on 20
October 2026; what they measured and fixed is in [living-capitals-findings.md](living-capitals-findings.md). The stores have a plan of their own,
[bot-stores-plan.md](bot-stores-plan.md).

What is asked: capitals that feel lived in, with people who **stay and are seen**, some of them keeping a private store while the broker is not done.

## Decisions (taken 20 October)

1. **Where store goods come from**: stock generated at the keeper's level, capped per keeper and per day; what bots really loot can be added once
   field bots carry their good drops home.
2. **Prices**: as written in [bot-stores-plan.md](bot-stores-plan.md), then moved by sales.
3. **Bots buy from players' stores**: yes, capped per bot and per day, and only below the fair price (milestone 5).

## Milestones

0. **Measure and explain. Done.** Sanctum's missing half (the centre bridges read as no-walk volumes), its rides (a boat and a lift, `RideLinks`),
   the fruitless flask trips, and the crowd test: about 40 characters in sight keep 40 fps. Open: the Protectors Hall floor is missing from the
   mesh, so its terrace stays empty.
1. **A capital day. Done.** `TownRoutine`: spells of 5 minutes (home, sitting, an errand to a town service, a visit), the same every day, starting
   at each resident's own moment. Watched: no FARMING in a capital, 5 to 17 of Sanctum's 117 residents moving at any moment.
2. **Private stores. Built 20 October**, see [bot-stores-plan.md](bot-stores-plan.md).
   - Five in ten residents of level 20 and over keep a store for a shift of 6 to 10 hours starting at any hour, at a market stall, renewed about
     every hour.
   - Themed stock generated at their level, priced from the template, the quality and the keeper's temperament, cheaper the longer it is unsold.
   - Check: a player buys from a bot in game, the kinah moves, and EXCHANGE_LOG has the line.
3. **Ambience.**
   - Groups of two to four facing one another, with emotes.
   - A rare wave or bow when a player stops in front of a bot.
   - Bots duelling on a square: duels exist, and onlookers are life.
   - Cosmetic pets and costumes, so a crowd is not all combat gear.
4. **Speech.**
   - Store adverts in the region channel ("Selling plate 30-35, see my store"), one every few minutes per capital, not per bot.
   - Calls for a group, echoing the offers `BotRecruiter` fills.
   - Cheap and highly visible, noisy if overdone: a cap per channel.
5. **Bots as buyers.** They browse players' stores and buy what sits under the fair price, inside a daily cap (decision 3). The broker comes next, on
   the same price rule. `BrokerService.getAveragePrice` **divides by zero** when the broker holds no listing (`counter = 0`): a trap for that phase.
6. **The day's rhythm.**
   - Fuller in the evening, emptier at night, by the server's clock.
   - Arrivals and departures through the teleporter and the obelisk, where a player can see them, rather than out of thin air.

## Later, to be decided

- **A companion keeps a store for its owner** (`//bot shop`): the absent merchant of official servers. It sells the owner's goods, never its own, at the
  owner's prices. The one exception to "an owned bot never sells" ([bot-gear.md](bot-gear.md)).
- **Crafters at the tables**, with the animation. Real crafting would stock the stores; it belongs to the economy phase.
- **A legion recruiting on the square**, once legions exist.
- **The boat's timetable**: the boat is the client's alone, so a rider vanishes on boarding rather than sailing with it.

## Risks

- **Memory and the client.** 150 bots a capital is already the cap. A keeper costs almost no ticks but still a character in sight, so the client
  ceiling decides.
- **Economy.** Generated stock and buying bots create items and kinah. Everything goes through the engine's own paths, so EXCHANGE_LOG traces it, and
  the daily caps are the valve.
- **A frozen store.** If a bot keeps a store, everything that moves it (rescue, director, sleep, group invitation) must close the store first.
  Otherwise `PlayerRestrictions` refuses the move without saying why.
