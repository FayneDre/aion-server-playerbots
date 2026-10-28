# Looking after itself: selling, dressing, drinking

Status: reference, moved out of built.md on 28 October 2026: what a bot does for itself between fights, and what each piece cost to learn.

## The vendor run

Wired into `PlayerBotAI.botTick`, right after the health check and before roaming, so a bot heals before travelling and never abandons a fight to go shopping. `//bot sell <name>` forces a trip for testing without waiting for a bag to fill. Verified in game: a bot walks to the nearest shop, sells, returns to its anchor.

**Arriving is not finding.** The shop spots come from spawn data — where an npc was placed, not where it stands. A bot that reached a spot with nothing on it re-issued a move to the point it was already standing on, arrived instantly, found nothing again, and did that for ever. Two changes end it: the bot walks to a shop keeper it can actually see rather than to a recorded coordinate, and a spot it reaches with no keeper in sight is dropped for ten minutes so the next trip goes elsewhere.

**Gear nobody here will ever wear is sold, whatever its quality.** Selling went by quality alone, so a green breastplate looted by a priest was neither worn nor sold and sat in the bag for good — and since a full bag is what sends a bot shopping, one such piece bought it an endless round of trips that sold a handful of greys and changed nothing. Two tests decide, both of them permanent: the wrong class, race or gender, and a mastery this class is never taught anywhere in its skill tree — plate for a priest, a bow for a templar. What the bot cannot wear because of its **level** is kept: it grows into that. "Does not have the mastery" is not an answer either, since masteries are taught as late as level fifty; "will never be taught it" is.

The trigger needed two conditions, not one: `hasFullBag(bot)` alone loops forever on a bag full of gear, quest items or anything rare, since nothing there is ever sold. `BotVendorManager.hasJunk(bot)` is required too.

**Selling goes through `TradeService.performSellToShop`**, the engine's own sale. It did not at first: that path gates on `PlayerRestrictions.canTrade`, which used to reject anything not `isOnline()`, so every sale silently failed, and its rules — price, sell limit, repurchase list, kinah — were copied into `BotVendorManager.sellJunk`. Once `isOnline()` was fixed at the root (below), the copy had nothing left to justify it and was deleted.

## Wearing the best of what it owns, and drinking

**A bot wears the best of what it owns.** Without this it fights for hours in the gear it spawned with, looting better pieces and walking past them in its own bag. Whether it is *allowed* to wear something is never asked: `Equipment.equipItem` already weighs the abyss rank, the mastery skills, the class, the level, the race, the gender and the room left in the bag, so trying is the question — the same bargain the skill code makes, and for the same reason: a second copy of the engine's rules would drift from it.

A weapon is only ever weighed against a weapon and armour against armour. Slots overlap in ways that make a free-for-all dangerous — a shield and a two handed weapon claim the same hand, so a shield that merely scored higher would strip the weapon and the weapon would not win it back. Anything finer than that is too strict: the first attempt insisted on the same item group, which is the *material*, and it blocked every real upgrade, since levelling gear crosses from leather to cloth and back with whatever drops.

**Worthwhile gear is bound to the bot before it is worn**, and this is what kept three attempts at equipping wearing nothing at all — `equipItem` does not refuse a soul bound piece, it asks about it and returns null. See [engine-traps.md](../traps/engine-traps.md); it is the clearest example of the whole family.

**A piece that scores higher can still be unwearable for good** — a mace a priest has no mastery for, a piece for the other race — and nothing short of trying says so. After a fruitless look the bot leaves its bag alone for a minute, or it would stop and sheathe on every tick of its life for one such item.

**Dressing is committed to, not waited for.** Reading a piece and binding one each take five seconds with the weapon away, standing still, undone by a single step or blow. A bot that farms is in its weapon stance almost always and spends its idle moments walking, so the quiet window never came on its own. The bot now stops, sheathes and holds its tick until the piece is on. Measured: three pieces worn per bot before, four and climbing after.

**Gear is identified before it is worn.** A piece that rolls bonus stats drops unidentified (`tuneCount == -1`) and the engine refuses to equip it, which is why the first version of this quietly wore nothing at all. Identifying costs nothing but five seconds: `CM_TUNE` calls `identifyItem` with no scroll when the item is unknown, and the scroll a player buys is for re-rolling a piece that has already been read. Only pieces worth wearing are read, and the judgement holds either way, since identifying rolls bonus stats without touching the level or quality it was judged on. Those five seconds are watched by an observer that cancels them if their owner moves or fights, so the bot holds its tick until they are over.

Which piece is better is judged on its level first and its quality second. That is a proxy and says so: comparing what items actually grant would mean weighing attack against defence against a resistance, which has no answer that holds for every class.

**Bots drink.** A class with no heal of its own had nothing between being hurt and sitting down, and healers spent their last mana without ever reaching for the flask that would have given it back. Potions are found by what they do rather than by a list of item ids — an item whose use casts a skill that heals — and used through the same path the client packet takes, so the prison, the abnormal states, the cooldown and "this would do nothing at full health" are all still the engine's call. That path only became reachable once `isOnline()` stopped meaning "has a socket".
