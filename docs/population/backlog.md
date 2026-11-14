# Population and the director: what is left in detail

Status: in progress: see each item; the order is the roadmap's.

Moved from the roadmap on 7 November 2026, when it became one page by theme: the detail of what is left, kept here with its numbers. The
order and the status of each item are in [roadmap.md](../roadmap.md).

## What the director still does not do

The pool is built: inhabitants are created asleep, and the director wakes and sleeps the countryside to match each region's target unseen. Since 17
October a hunter is woken for a ground of the plan nobody works, the nearest sleeper of that ground's side, and a few a review move from a ground
worked twice to an empty one: before, the field was woken in the pool's order, and Reshanta's upper layer was never woken at all. Three pieces were
deliberately left out.

- **Stocking.** *"Do I have a level-16 Asmodian scout?"*, growing one if not, retiring the oldest at the cap and freeing their names. Without it a
  population is whatever the populate setting created once. `populate` creates the busy plan, so a surge has sleepers to wake.
- **Pre-filling a map before a player arrives.** A teleport knows its destination before the client loads, and the character list is known on the
  selection screen. It is also what would let settlements sleep, which they cannot: a village is all within sight of the obelisk a player lands on.
- **Moving a bot that outgrew its region, across maps.** Within a map `BotDay.moveOutIfOutgrown` rehomes it; past the region's ceiling `BotPacing`
  stops it, a documented stopgap. A sleep and a wake on another map is cheap with the pool.

A region's band has two parts where its monsters do (`BotPlaces.isLivedAt`): Theobomos and Brusthonin were repopulated with 20 to 30 in their middle
band. **Reshanta's numbers are left as they are by decision** (18 October): 29 awake with nobody online (its villages and one hunter), 85 with a player
on it. Counting each set of islands as a region of its own is the way to raise them when wanted.

## Gaps

- **Bots do not yield to real players.** `BotTargetRegistry` stops two bots claiming one mob, but nothing yields to a person. Forty bots stripping a
  spawn or a gathering node make a worse server than none; this is a design rule to settle before a settled 2000.
- **Resident/adventurer leftovers**: check [resident-leftovers.md](../population/resident-leftovers.md) before removing any of it.
