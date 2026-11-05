# Playing with one's bots: starting level, dice, orders and one's own characters

Status: not started.

Three things a player alone with bots asks for once the group works, each small and each a server change the launcher can then show. Written on
5 November; the launcher's side is in its own roadmap.

## 1. The starting level

A new character starts at level 1 in Poeta or Ishalgen, or straight at the level 10 Ascension in Sanctum or Pandaemonium: the story for a first
character, the skip for the fifth. An option `gameserver.playerbot.start.level` (1 or 10, read at character creation) and, for 10, what a player
would hold there: the Ascension quest chain marked done (the engine's own completion, so the class change and the skills follow), the class chosen
at creation kept, the bind point and position in the capital, the starter gear of that level. Bots created for a player's group follow the same
option. **To check first**: whether the Ascension's completion is one quest or a chain whose steps the client reads separately, and what upstream
does with a character created above level 1 (`PlayerService.newPlayer`). Measured: a level 10 character of each class and race, created, logged in,
its skills, title and bind point as a played one has them.

## 2. Bots roll the dice

In a group the engine opens a roll for each item worth rolling for and waits for every member's choice. A bot today makes none, so every roll waits
for its timeout. **To check first**: what the engine does for a member with no client (no packet comes, it times out as a pass, or something else),
and how long the group waits. Then one pure decision, `BotLootRoll.choose(item, bot, group)`: **need** for a piece the bot's class can wear that
beats what it wears (the same comparison its dressing uses, `BotEquipManager`), **greed** for nothing, **pass** for the rest. A bot in a player's
group leaves the player everything else: it is there to help, not to compete. Answered within a second or two, so the window closes as soon as the
player has chosen. Measured: a run where the bots needed only their own upgrades and the rolls closed with the player's choice.

## 3. Orders and marks

Today a grouped bot follows, and `//bot attack` and `//bot come` move it. Three more orders, one per bot or for the whole group, kept until the next:

- **stay**: holds its place, fights what reaches it and casts in range, never walks off;
- **guard**: holds a place, fights within about 20 m of it and comes back to it;
- **passive**: follows and fights nothing, for pulling through a crowd or waiting out a patrol.

And two marks the leader sets on a monster, read by the playbooks beside the client's own marks (`BotMarks`): **kill first**, the group's focus;
**keep asleep**, which nobody damages while a bot that can sleep, stun or fear it keeps it so. As `//bot` commands first, then as the buttons of the
in-game bar ([living-world-plan.md](../../population/plans/living-world-plan.md), lot H). Measured: a dungeon pull with one mob kept asleep through
the fight, and a group left on stay that did not move when a patrol passed.

## 4. Playing with one's own characters

A player invites into their group another character of **their own account**, which comes in as a bot: its own gear, skills, stigmas and level,
and whatever it earns is its own when the player logs it in later. For a player alone it is the strongest reason to keep several characters: the
cleric levelled last month heals the templar played today.

What exists: `//bot spawn` loads any character, and `BotRoster.isSomebodysOwn` already treats a character of a real account as a person's, never the
director's to move, level or retire; a player's own bot never sells, dresses from or binds what is in its bag (`gear/`), and a bot in a player's
group follows the player's experience rate, not the world's pace.

What is to build:

- **The command** `//bot alt <name>` (and `//bot alt dismiss <name>`), open to players and not only to staff: the character must be on the caller's
  account, of the same race, offline, neither deleted nor being deleted, and within a level gap (an option, 10 by default) so a level 65 does not
  carry a level 20 through content. It is spawned beside the player and invited.
- **One body at a time**: while the alt is in the world as a bot, logging it in from the character screen is refused with a message, or dismisses
  the bot first (to choose by measuring what the engine allows: an account normally has one character in the world). A dismissed alt is saved, then
  taken out.
- **Tied to its owner**: it leaves when the player logs out, leaves the group or is away too long; it follows the player through instances and
  teleports like any grouped bot; it is saved every 5 minutes and when dismissed, and a crash loses at most those 5 minutes, as for every bot.
- **Its bag is the player's business**: nothing in it is ever sold, worn, identified or bound by the bot; trades with its owner use the companion
  trades already built (`//bot give`).
- **The launcher** lists the account's characters already: a button there to bring one into the group.

Measured: an alt brought in, fighting a dungeon with its owner, dismissed, then logged in by hand with its experience, loot and position as they
were; an attempt to log it in while it is in the world, refused or handled cleanly.
