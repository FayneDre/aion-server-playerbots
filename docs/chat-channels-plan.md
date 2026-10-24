# Bots in the chat channels

**Status: not started, on hold.** Planned on 24 October at the user's request and set aside to be done later: bots speaking in the Trade, Find
Group and Region channels, with far more variety than the shouts of [capital-life-plan.md](capital-life-plan.md), which stay. The user's answers
are at the end.

## Why

A shout is heard 95 m round the speaker. The channels are read everywhere on a map, and they are where a server looks lived in: who sells, who
looks for a group, what people talk about. Four sentences a kind repeat within minutes; a channel that repeats itself, or is flooded, reads as
bots at once.

## What the chat server does today (`chat-server`, an upstream module)

- A client connects to the chat server itself, after the game server registers it (`CM_PLAYER_AUTH` from the game server, then the client's own
  `CM_PLAYER_AUTH`). A bot has no client, so no chat session, and no way to speak in a channel.
- Channels are created when a client asks for one (`ChatChannels.getOrCreate`), from an identifier such as `@ trade_<meta> 1.0.AION.KOR`:
  type, meta, game server id, race. **Trade and Find Group are one channel a faction** (`RaceChannel.matches` ignores the meta); **Region is one a
  map and faction**, matched on the map identifier the client sends.
- A message is a channel, a sender (`ChatClient`: its id, its name as `Name@suffix` in UTF-16) and a text; `BroadcastService` sends it to every
  client in the channel (`SM_CHANNEL_MESSAGE`). The sender needs no connection of its own for that.
- The game server sends the chat server four packets: auth, player auth, gag, logout (`network/chatserver`).

## The design

1. **A new game server to chat server packet**, `CM_BOT_MESSAGE`: channel type, race, region identifier (Region only), the bot's object id and
   name, the text. On the chat server: find the existing channel that matches (none means nobody listens, and the line is dropped), build a sender
   for the bot (`ChatClient` with no connection, its name with the suffix the real clients use), log it as any line (`CHAT_LOG`) and broadcast it.
   Small and additive on both sides, so an upstream merge stays easy.
2. **The map identifiers of the Region channels**: the client names its map itself. Read once with `chatserver.log.channel.request`, then a
   table game server map id to identifier in the bot module.
3. **`BotChat`** in the bot module, beside `BotSpeech`: per faction, which line is due in which channel, who says it, and from what.
4. **The speaker is a real bot of the faction, awake**, so that a name read in a channel can be whispered to, inspected and invited. Region lines
   come from bots on that map; a Trade line from the keeper whose shelf holds what it names; a Find Group line from a bot whose level the
   instance it names admits (decisions 5 to 7).
5. **One faction never reads the other's lines.** The packet carries the speaker's race and the chat server only looks among that race's
   channels; a channel of the other race never matches, whatever its meta. A test on the chat server side holds this.

## What is said: true things, many ways

Every line reflects something real, said in many forms: a template is opening + content + closing, each drawn from its own list, with the
abbreviations people use (wts, lfg, pst, mp), so a kind has hundreds of forms. French, as the testers play.

| channel | content | from what |
|---|---|---|
| Trade, selling | a piece on the speaker's own shelf, at the price its store asks, the store's market | `BotStores` shelves, `BotStorePricing`; an item link if the client's chat format allows one (to check) |
| Trade, adverts | the speaker's own open store | the store name, as the shouts |
| Find Group | class, level, role, and an instance whose entry levels admit the speaker, for the look of it | the bot itself, `BotRole`, the instances' entry levels (data) |
| Region | greetings, the time of day, the map's places and creatures, small talk, a question one bot asks and another answers a few seconds later | the map's settlements and spawns, the server clock |

Not said: no buying (bots as buyers were dropped on 23 October). Find Group lines are there to make the channel look alive; a speaker that is
invited still answers as any bot does (`BotRequests`), but nothing brings it to its player from afar (decision 3 below).

## Pace

- Trade: a line every 60 to 120 s a faction; Find Group the same; Region every 2 to 3 minutes a map, and only on a map a real player is on.
- Never the same bot twice in 20 minutes, never the same content twice in 30.
- `//bot speech` covers the channels too (`off` silences everything); the rates are constants first, a config later if the testers want to tune.

## Milestones

0. **Read the identifiers**: Region map names and the name suffix, with `chatserver.log.channel.request` on for one session.
1. **The packet and the chat server side**, one fixed line from a bot, seen in each of the three channels in game.
2. **Trade**: shelves and adverts, with the item link if the format allows.
3. **Find Group**: class, role, level and instance, for the look of it.
4. **Region**: the small talk, then the questions answered by a second bot.
5. **Watched in game** with the user: variety over an hour, pace, nothing absurd.

## Risks

- **Whispers to a bot** go nowhere: it cannot answer, and none is wanted (decision 1).
- **The upstream module**: the chat server change is kept to one packet and one handler.
- **Noise**: the pace above, and the switch.
- **Item links**: if the client's link format cannot be reproduced, Trade lines name the item in plain text.

## Decisions (24 October)

1. **No answer to whispers.** A bot whispered to says nothing.
2. **Region small talk every 2 to 3 minutes a map**, on maps a real player is on (shortened from 2 to 4 on 24 October).
3. **Find Group is for the look of it.** It is not meant to have anyone invite a speaker. Kept for later: a speaker invited from afar brought to
   its player (the rescue puts a member beside its player on the same map; another map needs `BotInstances.moveBeside`, as recruits are).
4. **The shouts stay** beside the channels, and the three channels are wanted: Trade, Find Group, Region.
5. **Never seen by the other faction**: every line goes to the speaker's own faction's channel only (design 5).
6. **A Trade line is true of its speaker**: the bot that says it sells it, in its store open now, at the price it says. A piece sold or taken
   off the shelf is no longer announced; the line is built at the moment it is said, from the shelf as it stands.
7. **A Find Group line is true of its speaker**: the bot's level is within the entry levels of the instance it names, and its role is its own.
