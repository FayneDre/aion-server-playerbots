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
   come from bots on that map; Trade and Find Group lines from any bot of the faction.

## What is said: true things, many ways

Every line reflects something real, said in many forms: a template is opening + content + closing, each drawn from its own list, with the
abbreviations people use (wts, lfg, pst, mp), so a kind has hundreds of forms. French, as the testers play.

| channel | content | from what |
|---|---|---|
| Trade, selling | a piece on a keeper's shelf with its price, the store's market | `BotStores` shelves, `BotStorePricing`; an item link if the client's chat format allows one (to check) |
| Trade, adverts | the store itself | the store name, as the shouts |
| Find Group | class, level, role, and an instance of that level, for the look of it: nobody is meant to invite | the bot itself, `BotRole`, the instances' entry levels (data) |
| Region | greetings, the time of day, the map's places and creatures, small talk, a question one bot asks and another answers a few seconds later | the map's settlements and spawns, the server clock |

Not said: no buying (bots as buyers were dropped on 23 October). Find Group lines are there to make the channel look alive; a speaker that is
invited still answers as any bot does (`BotRequests`), but nothing brings it to its player from afar (decision 3 below).

## Pace

- Trade: a line every 60 to 120 s a faction; Find Group the same; Region every 2 to 4 minutes a map, and only on a map a real player is on.
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
2. **Region small talk every 2 to 4 minutes a map**, on maps a real player is on.
3. **Find Group is for the look of it.** It is not meant to have anyone invite a speaker. Kept for later: a speaker invited from afar brought to
   its player (the rescue puts a member beside its player on the same map; another map needs `BotInstances.moveBeside`, as recruits are).
4. **The shouts stay** beside the channels, and the three channels are wanted: Trade, Find Group, Region.
