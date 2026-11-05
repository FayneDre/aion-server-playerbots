# A living world: memory, learned ground, road careers, PvP, groups, the director, rumours and prizes

Status: not started (the density modes, lot A's first step, are built: `gameserver.playerbot.density.mode`, 5 November).

The aim is a world that lives whether a player watches or not. This plan gathers what would take the project there, in lots, after the density
modes of 5 November. **Out, by decision**: quests for world bots, patching the client's executables, rewards of our own invention (tickets,
bounties), changes to monster strength, a market other than the native broker, freezing the maps nobody is on, a move to another mesh format (ours are validated) and a
decision engine other than the class playbooks (validated in game).

Each item says what it is, what of ours it builds on, and how it is measured. Lots are in the proposed order; inside a lot, items are independent.

## Lot A: memory first, because everything else is bounded by it

**A1. A heap guard on the director.** Above a threshold, a review wakes nobody (departures go on as before), and wakes again below a lower one; a
line in the log and in `//bot pool` says so. The figure read is the old generation **after a collection** (`MemoryPoolMXBean.getCollectionUsage`),
not its raw usage, which counts garbage not yet collected and would pause the world for nothing. The thresholds come from measurement, not from a
round number: on 5 November the live set was already 69 % of the heap with 1158 bots awake, so a guard at 70 % would hold the world still at once;
something near 85 % on, 80 % off is the likely pair. It is the safety net the density slider needs: a percent set too high fills up to the guard and
stops, rather than taking the server down. Measured: with `FIXED` at 100 %, awake bots level off and the server stays up.

**A2. One budget for every navmesh, tiles released when nobody walks them.** On 5 November `short[]` tile heights were 603 MB of a 1771 MB live set
(569 MB of 1740 on 27 October), under a budget of 32 MB a map with 26 maps open. One budget for all of them, a least recently used set of tiles
released after some minutes unused and loaded again on demand, puts the memory where bots are. Measured with `tools/heap-snapshot.ps1` before and
after. Every megabyte saved is bots.

**A3. The density setting in the launcher**: the mode as a list, the percent as a slider bounded by what lot A measured, the guard's state shown.

**A4. A density that finds its own level.** A fourth mode, `FIT`: the director raises the countryside's share review by review while the heap after a
collection stays under a target, and lowers it above. The server then holds as many bots as the machine allows, on any machine, with nothing to
tune: what a repack installed on unknown hardware needs.

## Lot B: navigation

**B1. Ground that refused a bot is remembered.** A mesh cell where the mover is stopped (a probe refused, a bot stuck) is counted per map; a cell
refused for **two different bots** on two different days becomes blocked for the planner, so one monster standing in a path teaches nothing. It is kept
in a file per map, read at start, listed by `//bot nav learned`, and it expires after a week unless confirmed again. The list also feeds the mesh
generator, so a lasting fault is mended at its source rather than patched for ever. Measured: journeys given up per half hour (`tools/measure.sh`).

**B2. Lifts and moving platforms ridden by the bots.** It is what closes Theobomos Lab (72 %), Rentus (77 and 72 %) and the Pandaemonium dock, and
what would end the 13 s wait before a bot is put beside its player. A link "board here, ride, step off there" in the mesh, timed from when the
platform is at each end, measured in game. Large; after lots C and D unless dungeons come first.

**B3. Prepared corridors for long journeys.** The 5 s budget still runs out across Reshanta's 3 km; corridors between the places of the plan, built
offline by `NavmeshTool` and checked by the golden routes, would end that.

## Lot C: careers on the roads

**C1. Travelling merchants.** A bot of the pool with the career *merchant* walks between two villages of its region and opens a private store when
a player stops within a few metres, closing it when they leave. It reuses our bot stores (broker prices, goods by level). Its stock is **what the
region's bots really looted** and sold to it, finite and replenished by their hunting rather than generated; a merchant from a dangerous region
carries rarer goods. The world's loot then circulates, and a player learns where to look.

**C2. Road wardens.** Bots on a beat between two places of the plan, spaced out, who clear monsters near the road, heal or shield a player of their
faction in trouble, and in a PvP zone fight the invader. Their reward is a road that is safer, seen. They are also the first defenders lot D needs.

**C3. Caravans.** A merchant escorted by two or three wardens, moving as a group from one village to the next at set hours, announced in Region chat.
In a PvP zone the other faction's bots may attack it: a visible event that happens whether a player is there or not.

## Lot D: wild PvP, priority two of the project

The plan is [wild-pvp-plan.md](../../combat/plans/wild-pvp-plan.md); this lot adds to it.

**D1. Limits on a fight**, beside our shares and level band: pursuit no further than about 60 m from where it began and no longer than about 25 s,
then a pause; a fight started only when the visible strength of the other side is at most about 1.5 times ours; at most three attackers on one
target; retreat under a quarter of health. Each is an option in `playerbot.properties`, read at once like the rest.

**D2. Rift raids.** A group of bots of one faction walks to an open rift, goes through, hunts the other side for some minutes and comes back. A share
of leaders tries it, with a rest between attempts, a limit on the walk to the rift and a time after which a blocked trip is given up.

**D3. Raids that the world answers.** A raid is said in the region's chat, in the bots' own words ("des Asmodiens à Eltnen, près de la forteresse");
wardens and the bots nearby go to meet it. A player hears about a battle and can join it, even if no player started it.

**D4. Grudges.** A bot remembers who killed it (a few names, a few days): it greets them in chat, looks for them first, or keeps away from them if it
lost too often. Players feel known.

## Lot E: groups

**E1. Bots post in the Find Group window** for an instance of their level, and answer a player's own post with the missing role. We already fill a
player's offer (`BotRecruiter`); this is the other direction, with a ceiling of posts per tab so the window is not flooded.

**E2. Bots group with each other in the field.** Pairs and trios hunting together, met in the countryside like players. Today they never do
([roadmap.md](../../roadmap.md), "Bots never form a group of their own"). A small group also survives what kills a lone bot.

## Lot F: the director

**F1. A pool stocked by level band and role** (the roadmap's stocking): a share per faction and level band, and a missing tank or healer at a level
grown rather than left missing.

**F2. A day on the clock.** The countryside's share follows the hour: fuller in the evening, thinner at four in the morning, as on a real server,
multiplied with the density mode. A bot's friends see it log in and out at believable hours.

**F3. Sessions.** A bot leaves the world after a session of an hour or a few, and comes back later where it left, as a player does; its place is taken
by a sleeper. It refreshes the faces a player meets without anyone appearing in sight.

## Lot G: legions, priority five

**G1. Bot legions born of who played together**: a legion formed from bots that grouped often (lot E2), with a name, a chat and a home region, and
an answer to a player's invitation (the roadmap's first legion step).

## Lot I: a world that talks about itself and fights over its prizes

**I1. True rumours.** Bots say in the region's chat what the server knows and a player would want to: a named monster that has just appeared
("<name> est de retour près de <place>", the names read from the data), the other faction seen in numbers somewhere, a prize just taken (lot I3). Only true things,
said once, by a bot of the region, a few minutes late so it reads as hearsay rather than a radar; a rumour that would send a player to a fight far
above their level is not told to them. Built on the chat lines we have (`BotChat`, FR and EN).

**I2. Personalities.** Each bot is drawn once a temperament, kept for life: *chatty*, *quiet*, *brawler*, *loner*, *sociable*. It sets together
what is drawn separately today or not at all: how often it speaks and what about, its PvP temperament (the shares of `pvp.share.*`), whether it
groups in the field (lot E2) and answers invitations quickly, whether it camps a prize (lot I3). One draw, so a bot is consistent: the brawler who
shouts in Region is the one who attacks on sight. The shares of each temperament are options; the PvP shares become what the temperaments add up to.

**I3. Contested named monsters.** The open world's named monsters (HERO and LEGENDARY ratings, long respawns, a drop worth the trip) are prizes, and
bots want them too, as on a real server.

- *The catalogue*, from the data: every open-world npc of those ratings with its spawn point, level, respawn time and what it drops. To count
  first: how many per map, and which are quest targets rather than prizes.
- *Who goes*: a group of bots of the right level forms (lot E2) when the prize is due back, the director waking them if the region has none. A
  share of prizes is contested (an option, 0 to 100); the rest are left to players.
- *Knowing the timing*: bots learn when a prize was killed and come back for its respawn window, as campers do; a player who knows the window
  meets them there.
- *Fair play, both ways*: whoever hits first owns the fight, the engine's own rule. A bot never attacks a prize a player's group is already
  fighting, and gives up a camp a player was at before it. In a PvP zone, the other faction's bots may fight a player's group for it: the prize
  becomes a battle.
- *What it feeds*: the drop goes into a bot's bag, so it reaches a travelling merchant (C1) or the broker, and bots' gear finally keeps up with their
  level; the kill is a rumour (I1), the group's name with it.

Measured: prizes killed per day by bots and by players, and the time a prize stays up after its respawn; a player who comes on time gets it most
of the time, one who comes late finds it taken.

## Lot H: a window on the bots, inside the game

Two doors the stock client leaves open, neither of which patches an executable. The server side belongs here; packaging the client side and putting
it in place belongs to the launcher, a separate project.

**H1. Pages served by the game server, shown in the client's own browser.** The 4.8 client embeds a browser for its web shop and opens it with a
token (`CM_GF_WEBSHOP_TOKEN_REQUEST`, answered empty upstream: see the roadmap's in-game shop). The game server answers that token and serves pages
on a local HTTP port, authenticated by it: a panel of the player's bots (group, roles, orders, gear), the world's state (who is awake where, the
density, the guard), and later the in-game shop the roadmap lists. First step: prove the client opens a page of ours, and on which packet.

**H2. Interface addons.** The client loads interface addons (XML and Lua in `Plugin/<name>/<name>.pak`) signed against a key file beside it. A small
command bar for the group's bots (follow, stay, attack, gather) that sends the `//bot` commands the server already has. First step: an addon that
shows one button and sends one command, installed and removed cleanly by the launcher.

**H3. GM tools in the game.** The server's GM commands (`data/handlers/admincommands`) as forms on a page of H1, applied to the character played:
by family, *character* (level and experience, heal, resurrect, cooldowns, speed, invisibility, invulnerability, skills, titles, transformation,
race, name, dyes), *items* (found by name in the templates, with a quantity; a set; cube slots; a mail with attachments), *quests* (start, finish,
reset), *world* (spawn an npc, weather, time, a rift, a siege, an announcement), *players* (online, details, kick, ban, access levels). The
quickest ones (heal, resurrect, invisibility, invulnerability, speed) are also buttons of the H2 bar. **Who may**: an account of staff level, or
the client playing on the server's own machine, whoever runs the server being its GM; any other client never sees the page. A destructive command
(ban, delete, rename) asks for confirmation. The command runs as if the character had typed it, its answer in the game's chat and on the page.
Until H1 is proved, nothing of it is built; if the client cannot open our pages, the same forms go to the launcher instead.

**H4. What else belongs in the game rather than the launcher**: what is used while playing, with the character, its target and its place at hand.
The launcher keeps a plain version of each for when the game is closed.

- *Teleport*: the server's destinations as a page, the one the launcher's Account page offers today (`AdminChannel` `destinations` and `teleport`).
- *My characters*: the account's other characters, each with a button to bring it into the group ([group-options-plan.md](../../social/plans/group-options-plan.md),
  point 4); the same button on the H2 bar for the last one brought.
- *The world map*: per map, the bots awake and asleep, the players, the density, the memory guard; and the bots as a list by faction, with where
  each is and what it is doing. Never the named monsters' state (lot I3): that would be a radar, and their news travels by rumour.
- *Orders and marks* (stay, guard, passive, kill first, keep asleep): buttons of the H2 bar, as planned with the group options.
- *A tester's note*: a button of the H2 bar with a text field, sending `//bot note` with the place and the route taken as the command does today.

Left to the launcher, because they need the game closed, the server stopped or files written: install, updates, check and repair, the client's
language and renderer, several clients, the servers' dashboard, the server and bot settings, backups. Left to the client's own windows: Find Group
and the merchants' stores.

## Proposed order

1. A1, then A3, after the density measurement of 5 November; A2 after that measurement's histogram. 2. B1. 3. D1 to D3 (priority two). 4. E1, E2.
5. C1, C2, C3. 6. I2, then I1 and I3 (I3 needs E2). 7. F1 to F3. 8. B2 and B3, unless dungeons are brought forward. 9. G1, D4, A4 when the
rest stands. H1 and H2 can run beside any of them, since they touch neither the director nor the bots' behaviour.
