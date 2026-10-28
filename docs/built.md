# What is built

What works today, one paragraph a topic, each pointing to the document that says how it works. For what is **left**, see [roadmap.md](roadmap.md);
for every document, [README.md](README.md). Rewritten on 28 October 2026: the reasoning this file used to hold moved to the topic it belongs to.

## Where it stands

Bots are real `Player` objects with no connection, driven by `PlayerBotAI`, about 950 of them awake on 26 maps. They live, level and travel on their
own, fight with class skills, group with players, keep stores, talk in the channels and fly. Everything shipped up to `patch-2026-10-25-2` was
validated in game on 27 October, except duels, friends and trades with a companion. `//bot` lists the commands ([bot-commands.md](bot-commands.md)).

## By topic

- **The module** ([architecture/how-it-works.md](architecture/how-it-works.md)): why a bot is a real `Player`, the two core patches (`setAi`,
  `setMoveController`), the packages and their layers, `isOnline()` meaning "is present" (whatever needs the socket tests the socket), saving every
  5 minutes and on a clean shutdown. Driving a body: [architecture/moving-a-body.md](architecture/moving-a-body.md).
- **Population** ([population/how-it-works.md](population/how-it-works.md)): one kind of bot that levels and travels; villages always full, the
  countryside breathing with attention; inhabitants created asleep in a pool the director wakes; careers that move a bot's home as it grows. What the
  pool cost to build: [population/history/the-pool.md](population/history/the-pool.md).
- **Combat** ([combat/how-it-works.md](combat/how-it-works.md)): two ticks, targets chosen alone or read from the group's aggro, skills from the class's
  playbook then the generic order read from the data, retreat from a fight it cannot win, death and revival.
- **Classes** ([classes/how-it-works.md](classes/how-it-works.md)): six playbooks from guides (templar, chanter, cleric, ranger, gladiator,
  sorcerer), rules by skill group, pure decisions, data tests.
- **Gear** ([gear/how-it-works.md](gear/how-it-works.md)): what a bot wears and how it is upgraded, stigmas; selling junk, dressing in the best of what
  it owns and drinking potions: [gear/looking-after-itself.md](gear/looking-after-itself.md).
- **Navigation** ([navigation/how-it-works.md](navigation/how-it-works.md)): a generated navmesh for 26 open-world maps and 45 dungeon geometries,
  A\* in one or two passes, glide, ride and flight links, a mover that walks the plan and probes only where the mesh is silent, bounded rescues.
- **Flight** ([flight/how-it-works.md](flight/how-it-works.md)): take-off, cruise and flown descents, following a flying player, Reshanta's islands
  crossed by air.
- **Groups and players** ([social/how-it-works.md](social/how-it-works.md)): invitations, following, instances, the Find Group window, requests,
  the chat channels. Why groups work the way they do: [social/groups.md](social/groups.md).
- **Capitals** ([capitals/how-it-works.md](capitals/how-it-works.md)): a town day, private stores at broker prices, high-level visitors, shouts.

## Known limits and traps

Known limits are kept in one place, [roadmap.md](roadmap.md) ("Known limits"), reviewed with each patch. What the engine and the data do to a bot that
nothing warns about is in [traps/](traps/): where to look first when a bot does nothing and says nothing about why.
