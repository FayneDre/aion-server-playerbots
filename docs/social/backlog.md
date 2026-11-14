# Groups, dungeons and players: what is left in detail

Status: in progress: see each item; the order is the roadmap's.

Moved from the roadmap on 7 November 2026, when it became one page by theme: the detail of what is left, kept here with its numbers. The
order and the status of each item are in [roadmap.md](../roadmap.md).

## Dungeons and instances

**The largest player-facing value in the project**: a solo player can run group content. Everything else makes the world look alive; this makes it
playable alone, and it is the next thing worth doing.

What exists: group roles, six class playbooks, positioning, marks, a squad of bots that fights as a group, and **45 dungeon meshes** with glide links,
each put through a reach check ([dungeon-navmesh-plan.md](../navigation/history/dungeon-navmesh-plan.md)). What is missing:

- **Instance entry: built and watched 18 October** (Noschana). A grouped bot follows its player into the instance and out again (`BotInstances`),
  revives at the entrance, and a bot saved inside starts at the exit; `BotMapChange` spawns a bot a map change would leave waiting for a client's
  `CM_LEVEL_READY`. The run showed bots slow to catch up and never gliding: they now keep a route within 8 m of a moving leader and copy the
  leader's glide from its takeoff (`LeaderGlides`). A player who logs out inside is waited for five minutes, then the bots leave the group and the instance (`BotInstances`). Both validated in game on 27 October.
- **A bot cut off from its player** in an instance (a teleporter, a lever, a lift, a windstream, a door the player's key opens) is put beside them
  at any distance after about 13 s (`PlayerBotAI.freeItself`, 19 October); before, past 200 m, it went to its home's coordinates on the instance's
  map. Validated in game on 27 October.
- **The instance meshes** (54 files for the 45 geometries) are walked: Noschana's was. On 19 October floors the generator dropped were put back in 20
  of them (Steel Rake 99 %, the Danuar Sanctuaries 94 and 95 %, the Dredgions 96 to 99 %, Sauro 91 %), and Esoterrace reads 99 % once its
  windstreams' landings count as arrivals, and both Dragon Lord's Refuges 100 % once the teleporters their handler codes count too. Left under 80 %: **Theobomos Lab** (72 %, its lift after the elemental rooms; Queen Arachne's room, its
  first boss, walked to since a face that is no wall in game was left out) and **Rentus** (77 and 72 %: a lift that appears at Kuhara's death, the tester says, which no mesh holds).
- **Doors, lifts and windstreams** are not used by a bot itself: it is put beside its player instead. Links "walk to the object, use it, arrive
  there" are the next step if 13 s turns out too long ([dungeon-navmesh-plan.md](../navigation/history/dungeon-navmesh-plan.md)).
- **Bosses**: a bot that simply fights correctly clears most low-level instances; mechanics are a later refinement, not a prerequisite. Positioning
  against a boss has never been seen, because the only Elyos boss in reach outdoors stands off the mesh.
- **Levels**: no level 50 bots for the Theobomos instances and Dark Poeta (asked by a tester on 9 October); they exist on another test server,
  whose feedback is awaited.

## Group play

What is left once roles, playbooks and positioning exist:

- **The other classes' playbooks**: assassin, spirit master, bard, gunner, aethertech. The pattern is set (`ClassPlaybook`, rules by
  skill group, a data test that checks every group against the skill templates); each is a plan from the guide's document, a playbook and a test.
  **Spirit master, aethertech, bard and gunner come first**: on Reshanta's upper layer they died 36 times of 40 in half an hour, four bots against
  the other 21's four deaths, on the generic rotation.
- **Damage waits for the tank to hold.** It focuses the tank's target, but opens at the same instant, so the first blow can pull the mob off.
  `AggroList.getTarget(MOST_HATED)` can answer "the tank holds it".
- **Composition: built and watched 18 October.** An offer in the Find Group window is filled with bots, a tank, a healer and a support first, at the
  instance's levels, counting the player's own role (`BotRecruiter`); recruits go home when released. Needs `form_anywhere` in the server's config. Left: the Recruit Group Members tab, alliances, and a pool stocked by role,
  since a missing templar or cleric at a level is a missing tank or healer (see the director below).
- **Bots never form a group of their own.** They join a player's, an offer's or a `//bot squad`; two bots in a field never party with each other.
- **Tester points still open**: the templar that "drops the fight" (needs the tester's log or the time and the bot's name; a grouped bot no longer
  breaks off from any fight since 17 October, which may be it), the cleric against an assassin's rune ([cleric-plan.md](../classes/history/cleric-plan.md)).

## Asked in game, 19 October

The list "missing features in game", each with what stands today:

- **Trading with one's companion**: built 19 October, with its owner only; `//bot give` hands over the companion's side ([player-interactions-plan.md](../social/plans/player-interactions-plan.md)).
- **Adding a bot as a friend**: built 19 October; a bot shows online while awake.
- **A legion's invitation**: not answered. The first step of the legions section below.
- **Cohorts** (alliances): a bot answers an alliance invitation but acts as if the whole alliance were its group ([cohort-plan.md](../social/plans/cohort-plan.md)).
- **Duels**: built 19 October; a free bot within 10 levels accepts and attacks, and never asks for one.
- **The in-game shop does not work**, and is not the bots' work: the server has none. The 4.8 client opens a web shop with a token
  (`CM_GF_WEBSHOP_TOKEN_REQUEST` answers an empty one, a TODO upstream); the older shop packets are commented out and `config/ingameshop` is unused.
  Either a small web shop answering the token, or reviving the old packets if this client still reads them: a decision, then a project of its own.
- **The arenas** (four or five kinds): not started; they are instances of their own, after the dungeons.
- **A living world**: built 20 to 23 October, a town day and 40 bot stores a capital ([living-capitals-plan.md](../capitals/history/living-capitals-plan.md)).

## Gaps

- **The chat server is off upstream** (`gameserver.chatserver.enable = false`, no password): a server that wants channels, a tester's own
  included, sets both in its `mygs.properties` (or in `gameserver.properties` and `network.properties`).
