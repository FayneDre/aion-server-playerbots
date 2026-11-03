# Feedback of 3 November: the broker's material prices, stigmas and event gear

Status: changed, not yet checked in game. `BrokerHouseRulesTest` covers the gathered price; the rest reads item data and runs only on the server.

The tester went through the broker again after the 1 November changes (see [feedback-2026-11-01-broker.md](feedback-2026-11-01-broker.md)).

## What changed

| The tester wrote | The house now |
| --- | --- |
| Looted materials must not stop at 100 000: some are worth several million. | No ceiling on a material between players (looted or gathered). The 1000 kinah minimum stays. |
| Gathered materials: base the price on the item level, which follows the gathering tier (level 50 is the 400 to 500 extractions). White level 50 is about 10 000, white level 10 between 1000 and 2000; green is three times white, blue three times green; a gold level 50 is about 270 000. | `BrokerHousePricing.gatheredMarket`: the white price is the line from 1500 at level 10 to 10 000 at level 50 (and on above), times 3 for each quality step (green 1, blue 2, gold 3, epic 4, mythic 5). Gold level 50 is 270 000. Applies to the `GATHERABLE` item group. |
| Stigmas: price times 20. | Stigmas are 20 times the data's price (it was 100). |
| "Looks better but few choices" (2 armour pieces, no weapon, no mythic accessory). | Every mythic weapon, armour piece and accessory is listed, bound or not (`BrokerHouseStock.isMythicGear`), see below. |
| A level 65 abyss plate marked "Event Equipment" in its tooltip is a unicorn: sold at 100 billion it would go. | The event gear is never listed, see below. |

Listings the house already holds above what it would ask today are withdrawn on its next pass (`BrokerHousePricing.isOverTop`): before, only those under the
floor or over a ceiling were, so the old stigmas at 100 times would have stayed. The kept listings of materials that now ask more than their ceiling go the same way.

## Mythic gear

The data binds almost every mythic armour piece and weapon (their mask has no tradeable bit) and the house listed tradeable goods only, which left the two or three the
tester counted. The house now lists the mythic weapons, armour pieces and accessories whatever their mask says (about 2300 templates, the heads, the sword and
armour sets of each class), as a live game's broker would show them. The other rules (test pieces, placeholder prices, race) still apply, and the price ranges of 1 November
(500 million to 1 billion a weapon, 200 to 400 million any other piece) stay.

## Event equipment

The client marks as "Event Equipment" the gear whose client name opens with `event_` (the server's name opens with `Bootleg`: `Bootleg Custodian's Divine Breeches`,
`Bootleg Lunatic Modor's Cipher-Blade`). Four of them were among the mythic pieces the house held, which are the "two armour pieces" the tester saw. Both markers are
now refused by `BrokerHouseStock.isPlayerGoods` (about 390 mythic templates), and the listings already held are withdrawn on the next pass.
