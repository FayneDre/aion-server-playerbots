# Feedback of 3 November: the broker's material prices, stigmas and event gear

Status: changed, not yet checked in game. `BrokerHouseRulesTest` covers the gathered price; the rest reads item data and runs only on the server.

The tester went through the broker again after the 1 November changes (see [feedback-2026-11-01-broker.md](feedback-2026-11-01-broker.md)).

## What changed

| The tester wrote | The house now |
| --- | --- |
| Looted materials must not stop at 100 000: some are worth several million. | No ceiling on a material between players (looted or gathered). The 1000 kinah minimum stays. |
| Gathered materials: base the price on the item level, which follows the gathering tier (level 50 is the 400 to 500 extractions). White level 50 is about 10 000, white level 10 between 1000 and 2000; green is three times white, blue three times green; a gold level 50 is about 270 000. | `BrokerHousePricing.gatheredMarket`: the white price is the line from 1500 at level 10 to 10 000 at level 50 (and on above), times 3 for each quality step (green 1, blue 2, gold 3, epic 4, mythic 5). Gold level 50 is 270 000. Applies to the `GATHERABLE` item group. |
| Stigmas: price times 20. | Stigmas are 20 times the data's price (it was 100). |
| "Looks better but few choices" (2 armour pieces, no weapon, no mythic accessory). | A data fact, see below. |
| A level 65 abyss plate marked "Event Equipment" in its tooltip is a unicorn, not a legitimate piece. | Not done: no attribute of the item data carries that mark, see below. |

Listings the house already holds above what it would ask today are withdrawn on its next pass (`BrokerHousePricing.isOverTop`): before, only those under the
floor or over a ceiling were, so the old stigmas at 100 times would have stayed. The kept listings of materials that now ask more than their ceiling go the same way.

## Why mythic gear is scarce

Almost every mythic armour and weapon template of the data is soul-bound (its mask has no tradeable bit), and the house lists only tradeable goods. By the data's count,
about 90 tradeable mythic templates have no item group of the gear kinds, and 49 more are the head pieces. Listing the bound ones would put on the market what a player
cannot sell, so the house does not.

## Event equipment: open

The piece in the screenshot is named by the client (`Dreaming ... Holy Plate Breastplate`); that name is not in the server's item data, which holds the
`Archon Executor's` and `Guardian Executor's` pieces under other names with the same attributes as the rest of the abyss gear. Its template id is needed to tell it
from them: with it, either a structural marker is found (compare it with its twin) or the id goes to the broker blacklist (`gameserver.playerbot.broker.blacklist`).
