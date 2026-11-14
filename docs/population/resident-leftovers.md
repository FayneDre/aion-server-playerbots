# What is left of resident and adventurer

Status: not started. Nothing is removed; this is the list to check first.

The distinction between a resident (a bot that gains no experience) and an adventurer (one that levels) was dropped from the design, see
[the-pool.md](history/the-pool.md) under "Retired: residents and adventurers". Parts of it are still in the code, the data and the documents.

## Where it still shows (4 November)

- **Code**: about 270 lines of the bot module mention "resident": `BotRoster`, `BotPacing`, `BotCommands`, `BotInbox`, `BotPopulator`,
  `BotRecruiter`, `BotVisitors`, `BotStoreStock`, `BotVendorManager`, `PlayerBotEnterWorldService`, and the AI classes (`PlayerBotAI`,
  `Occupation`, `TownRoutine`, `BotDay`, `BotDeath`, `BotRescue`, `BotConvalescence`, `LostTrail`) and movement (`BotFlight`, `BotMoveController`).
- **Command**: `//bot kind <name> [resident|adventurer]` and its line in [bot-commands.md](../bot-commands.md), and the inbox's `kind`.
- **Database**: `playerbot_characters.resident` is 1 for 2004 of 2017 bots.
- **Documents**: the speech command still speaks of a resident of 20 and over asking for a group.

## Before removing anything

1. For each use, say what it still decides today (pacing of experience, where a bot spawns, what it says, how it is stocked), and whether the
   word is only a name for something that stayed (for instance "a bot that lives in a place").
2. Check what reads the column and what writes it, and whether the launcher or a tool reads it (the launcher does not since 4 November).
3. Remove in one patch with the SQL for the column (`game-server/sql/update.sql`) and the documents, and run the tests: the removal must not
   change what a bot does.
