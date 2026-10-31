# Bot chat: fun and troll lines in Region, and a choice of French or English

Status: not started: the plan is written, the decisions at the end are the user's.

Written on 31 October 2026 from the tester's feedback of 25 October ("add fun and troll discussions to the Region channel") and a second request: the
bots' chat in **French or English, chosen in an options file**, on every channel. What exists is in [../how-it-works.md](../how-it-works.md) and
[../history/chat-channels-plan.md](../history/chat-channels-plan.md); this plan changes what is said, not who says it nor when.

## What there is today

- `BotChat` decides who speaks, where and when (Region, Trade, Find Group; a line every 20 to 40 s per map and faction, never the same bot twice in
  20 minutes nor the same content in 30). `BotChatLines` holds the words: **about 160 French strings in Java constants**, built from opening, content and
  closing. `BotSpeech` shouts in the capitals (store adverts, calls for a group) with 8 more.
- A Region line is one of five kinds by weight: greeting 15, what the speaker hunts 25, progress to the next level 15, a question another bot answers
  4 to 15 s later 30, chatter the remaining 15. Chatter is the only kind that is neither true of the map nor of the speaker, and it is ten lines.
- There is no option for the language, and nothing outside `BotChatLines` and `BotSpeech` writes a line a bot says. Whispers go unanswered.
- Item links (`[item:id]`) are drawn by the client in its own language. The names of places and creatures come from the server's data, which is English;
  today they sit inside French sentences ("Je farm les halabana sentinel vers Morheim").

## 1. The words leave the code

All the lines go into one file per language, `game-server/data/bot_chat/chat_fr.txt` and `chat_en.txt`, **packaged in the jar as resources** (an entry in the pom's
`<resources>`, as the SQL schema already is): `deploy.ps1` copies the jar and the handlers and nothing else of `data/`, so a file beside the server would never arrive. A section per kind, one line a row, the placeholders of `String.format` kept as they are:

    [greeting.morning]
    Bonjour tout le monde
    bonjour %s
    [banter.setup.classes]
    Un tank qui meurt, c'est un buff pour le groupe, non ?

- `BotChatLines` becomes a reader of the language it was given, with the same methods; the code that picks a kind and a line does not change.
- **A test holds the two files together**: every section of one exists in the other, a line has the same number and order of placeholders in both, none is
  over the length the chat window takes, none is empty or doubled, and each line formatted with sample arguments does not throw. A missing line in the
  chosen language falls back to the other one and says so once in the log.
- First step with no change of behaviour: the 170 existing strings (160 and the 8 of the shouts) written to `chat_fr.txt`, and a test that the reader gives back the same set.

## 2. The language in the options file

`config/main/playerbot.properties` gets

    # FR or EN: the language of everything bots say in chat channels and shouts
    gameserver.playerbot.chat.language = FR

read into `PlayerBotConfig.CHAT_LANGUAGE` (an enum, `volatile` like `POSITIONING`). One value for all channels, as asked. Per channel overrides
(`...chat.language.region`, `.trade`, `.findgroup`, `.shout`) cost four lines and let a server speak English in Trade and French in Region; they are
off unless wanted (decision 2).

- **English is written, not translated**: the trade abbreviations are English already (WTS, LFG, LF1M, inv pls), the greeting lists, the hours and the
  hunting talk are not word for word. Both sets get the same kinds with the same weights, so the pace does not depend on the language.
- Names of places and creatures stay what the data gives (English), in both languages. In French that reads as the players say them; making them French
  needs a glossary like the skills' one (`docs/classes/glossary`), and is out of this plan (decision 3).
- `//bot speech lang FR|EN` changes it on a running server for the test, and goes into [../../bot-commands.md](../../bot-commands.md) (`check-bot-docs.ps1`
  refuses the deploy otherwise). The lines are read at startup; correcting a line is a deploy, which is a few minutes and no database change.

## 3. Fun and troll lines in Region

The tester asked for a channel that feels like players mucking about. What makes it read as players, and not as a list of jokes read at random:

- **A new kind, banter**, weight 20 of a hundred taken from the questions and the chatter (30 and 15 today): a line to the room, or a **two-bot exchange**: a setup
  and, 4 to 15 s later, an answer or a punchline from another bot of the map (the mechanism of the question already built, so no new machine).
- **What they joke about**, all of it true of the game or of playing: the enchant that fails ("+10 ou rien", "mon +9 vient de partir en fumée"), dying to a
  mob of the starting valley, being lost, the long flight, kill stealing, the drop that never comes, farming one mob for an hour, gliding into a wall, the tank
  who pulls everything, the healer who rests at the wrong time, "WTS mon sommeil", Elyos and Asmodians teasing each other from a distance, the server clock.
  Each kind has 10 to 20 lines, and several have a setup and an answer.
- **Reactions to what happens on the map**, from facts the server has: a bot just died near the speaker ("rip", "gg la mort"), a level up ("ding"), a rare monster
  killed, night falling. Rare, and each fires once for a given event.
- **Troll means teasing, in play**: about classes, about luck, about the speaker itself. Never about a real player (no line takes a player's name; a bot
  answers a question another bot asked, not one a player typed), nothing sexual, no insult, no politics, no religion, no hate, no real brands or persons.
  A **test with a list of forbidden words** (both languages) runs over the files, and the tester reads the new lines before they are shipped.
- A bot never says it is a bot. A joke about bots is the one place this could break, so none is written (decision 4).
- The repeat rules stay: a joke is not heard twice on a map in 30 minutes, and each bot has its own habit (a bot that jokes jokes more often, a
  quiet bot never does), fixed by its id like the formation's slots, so that the same names are the funny ones all week.

## Order of work

| # | What | Verified by |
|---|------|-------------|
| 0 | The 170 strings out of Java into `chat_fr.txt`, no change | A test that the set of lines is the same |
| 1 | `CHAT_LANGUAGE`, the reader, the parity test; `chat_en.txt` of the existing kinds | The test; a 10 minute watch of Region in each language |
| 2 | Shouts and Trade and Find Group in English | The same watch, in the capitals |
| 3 | The banter kind: the lines in French, the exchanges, the reactions | The tester reads the list; 30 minutes in Region |
| 4 | The banter in English; `//bot speech lang` and its documentation | The parity test; the command from the game |
| 5 | A measure: lines an hour, repeats, a bot that speaks twice in the quiet time | `tools/measure.sh` counts `BotChat` lines per kind |

About two days of work, most of it writing lines. Nothing here touches movement, combat or the chat server's packet (`CM_BOT_MESSAGE` carries the text
as it is, accents included, as it does today).

## Decisions for the user

1. **How far the troll goes**: the limits above (no player, no insult) are the proposal; is that the tone, or milder, or a real-chat edge?
2. **One language for everything, or per channel**: the option file as asked has one value; per-channel keys are a few lines if wanted.
3. **Place and creature names in French** when the language is FR: the data has only English; a glossary is a separate piece of work.
4. **Bots joking about being bots**: proposed no, so that a player never reads a line that contradicts a character. The other way is a few lines the tester may prefer.
5. **A third value, BOTH**: a bot picks the language of its own character at random for a mixed channel, as a server of two communities reads. Not in the plan.
