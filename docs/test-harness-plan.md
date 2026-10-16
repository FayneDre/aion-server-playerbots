# Test harness: plan

Status: in progress, step 1.

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
   `patch-YYYY-MM-DD` (with a suffix for a second one that day) and pushes the tag; a patch note names it. `deploy.ps1` warns when the tree is dirty,
   since a build from uncommitted code has a revision nobody can check out.
4. **Detailed logging on demand.** `//bot debug [on|off]` raises the bot module's combat loggers to DEBUG at runtime, so every cast, with its target, is
   in the log of a test session without editing `logback.xml` or restarting.
5. **A glossary and a feedback template.** `docs/skill-glossary.md`: the French client names the guides and testers use, mapped to skill groups;
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
