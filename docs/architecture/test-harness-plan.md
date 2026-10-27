# Test harness: plan

Status: all seven steps built; a squad was watched fighting on 16 October (see the end), and squads have since been run at level 24 in Eltnen,
40 in Heiron and across Reshanta's upper layer.

Why: on 16 October a tester's feedback was fixed and shipped with every group rule unit tested and none seen in a fight, because a group fight needs a
player in the group; and the one point left open, a templar that drops the fight, could not be looked at because the tester's log was on another
server, 18 MB long, with no way to find the minute that mattered. This plan is the tooling that closes both gaps.

## Steps, in the order they pay

1. **`//bot note <text>`.** Writes one line to the log, `BOT NOTE`, with the text, the time, the caller's map and position, and for each bot in the
   caller's group (or within 30 m when there is none): class, role, health, mana, target, the target's target, and what the bot is doing. A tester types
   it the moment something looks wrong; the line is what the log is searched by.
2. **`tools/collect-feedback.ps1`.** Finds every `BOT NOTE` in a log, keeps the few minutes around each (two before, one after by default), and writes
   them with the server's revision into one zip a tester can send. Works on a log copied from another machine.
3. **Versions.** `//bot version` prints the revision the server runs (the jar's manifest already carries it). `tools/tag-patch.ps1` tags a clean HEAD
   `patch-YYYY-MM-DD` (with a suffix for a second one that day), pushes it only with `-Push`, and refuses a deployed jar that is `-DIRTY` or older than the code; a patch note names it and its revision. `deploy.ps1` warns when the tree is dirty,
   since a build from uncommitted code has a revision nobody can check out.
4. **Detailed logging on demand.** `//bot debug [on|off]` raises the bot module's combat loggers to DEBUG at runtime, so every cast, with its target, is
   in the log of a test session without editing `logback.xml` or restarting.
5. **A glossary and a feedback template.** `docs/classes/skill-glossary.md`: the French client names the guides and testers use, mapped to skill groups;
   `skill-lookup.ps1 -Name` searches it too. `docs/tester-feedback.md`: what a useful report holds, with a template in French.
6. **Squads: a group with no player in it.** `//bot squad form <leader> <member>...` groups spawned bots under a bot leader, brings them to it, and
   marks the group as a squad: the leader is followed although it is a bot, and the group is not dissolved for having no player. The leader keeps
   playing on its own, picking fights round its camp, and the members follow and assist it as they would a player. `//bot squad disband`,
   `//bot squad` to list. Outdoor maps first; taking a squad into an instance is a step of its own.
7. **A command inbox.** The server reads `bot-commands.txt` in its folder every few seconds and runs each line as a `//bot` command with no caller,
   answering in the log. `tools/bot-command.ps1` appends to it. This is what lets a squad scenario run, and be measured, without a game client.

Commits: one per step, each after its own check.

## What is decided

- A squad leader is a bot, and that is only allowed for a squad. Outside one, `leaveIfLeaderless` still dissolves a group with no player, for the reason
  it gives: an ordinary bot cannot lead.
- A note is a log line, not a database row: the log is what a tester already sends, and what the other tools read.
- Commands without a caller are the ones that need no position; a command that needs one says so in its answer.

## What the first squads showed (16 October)

Six level 24 Elyos (`Squadtank`, `Squadheal`, `Squadsorc`, `Squadglad`, `Squadchant`, `Squadrang`, kept in the database for the next run) at the kerubian
camp of Eltnen, `teleport Squadtank 210020000 188 2143 216` then `squad form Squadtank Squadheal Squadsorc Squadglad Squadchant Squadrang`. Within the first
four minutes the squad showed two faults no unit test could have, both in groups with players too, and both fixed the same day:

- **Mates turned on the chanter.** A buff of the "indirect" kind puts its caster on the target's aggro list, which fires the bot's attack event; three mates
  at once "retaliated" against the chanter on every Protectorate's Prayer, dropped the fight at once and lost the walk they were on. Six times in four
  minutes before, none after.
- **The gladiator taunted.** Taunt is a warrior skill, so the gladiator has it, and the generic order took it for an attack: three casts in four minutes,
  each one pulling the enemy off the tank. Pure taunts are now the tank's alone in a group. None after.

Also seen: a cleric that resurrected seconds before the squad formed stayed at its obelisk, 770 m away, and cast Hand of Reincarnation every second for
three minutes, each cast cut short (it was being hit). Why the teleport did not take is not known; the squad now checks two seconds after forming and brings
over whoever has not arrived, saying so in the log. And a member in a group no longer convalesces at the quiet spot by its home.

**The tester's point, measured.** Five minutes each against the same camp, nothing else changed:

| Mode | Fights started | Per minute | Give-ups | Deaths |
|---|---|---|---|---|
| `spread` (every bot placed) | 69 | 14 | 9 | 0 |
| `standard` (nobody placed) | 116 | 23 | 16 | 0 |

Placing against ordinary monsters costs two fights in five. In `spread`, 31 of 200 melee samples and 40 of 200 tank samples counted as well placed, so the
placing that costs that much is not even achieved; that is the work left on positioning, for bosses, where `AUTO` uses it.

**Next:** every target given up in both runs (13 of 13) was the ranger's. That, and not the templar, is the first thing to look at with a squad.
