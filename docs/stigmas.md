# Stigmas

How a bot gets its stigma stones, and the four things the engine demands before one can be socketed. Split out of
[combat-skills.md](combat-skills.md), which it was outgrowing.

A build past level 20 **is** its stigmas, so a bot without them was being measured as a character missing half of itself. `BotStigmaFitter` sockets
them, and nothing in it reimplements the engine: the sockets, their count, the kinah and the skills all belong to `StigmaService`, which the ordinary
equip path already calls.

Which stones a class may wear is read rather than listed, through a join the data supports in only one direction:

```
skill_tree.xml   <skill skillId="500" minLevel="20" classId="GLADIATOR" stigma="1"/>
skill_templates  skill_id="500"  group="FI_LOCKDOWNIMPACT"
item_templates   id="140001115" "Lockdown"  <stigma gain_skill_group1="FI_LOCKDOWNIMPACT"/>
```

The stone itself cannot be filtered on class — plenty of stigmas state a required level of 20 for all seventeen — so the tree is the only honest
source. It is also the only place the **tier** is stated: `stigma="1"` is a regular socket, `"2"` and `"3"` advanced, `"4"` linked and granted on its
own. The stone does not say, because `ItemGroup.STIGMA` names all six sockets at once.

Measured across the data: every one of the eleven advanced classes finds 9 to 13 regular stones by level 25 and advanced ones from 45, so no class
comes up empty.

Three traps, each of which would have failed quietly:

- **The socket count is zero until the stigma quest is complete** (1929 Elyos, 2900 Asmodian), whatever the level. A bot could hold every stone in the
  game and socket none. Granted at creation, exactly as the ascension quest already was.
- **`chargeable` reads backwards.** Every stone exists twice, as itself and as an inert copy granting the same group. The ordinary one is
  `chargeable="true"` — it can be enchanted with a duplicate of itself — and the inert copy is false. `StigmaService.addLinkedStigmaSkills` settles
  it: it refuses the linked stigma unless all six sockets hold chargeable stones.
- **The skills are not stored with the character.** The stones persist as equipment; what they grant does not, and `PlayerEnterWorldService` hands it
  back on every login. A bot that did not do the same would have been fitted once and gone silent at the first restart, still visibly wearing them.

Which stigmas, out of the fifty or so a class can reach, is the role's question: a tank takes the ones that hold aggro, a healer the ones that heal,
and everyone else — and all of them as the tie-break — the stated power that already orders every other skill here.

