# Group positioning: plan

Plan, not built. Source: The guide's page on positioning for the members of a group (two pages and a diagram, in French) and his page on aggro distance.
Where the module stands today is in [group-roles.md](group-roles.md) and [navigation-prototype.md](navigation-prototype.md); this file is the distance
between that and what he describes. Step 0, the measuring, is done and its figures are below.

## What he asks for

The aim: every class uses its skills in the best conditions. The constraint over everything: **a bot's position must not draw new enemies.**

| Where | Classes (his names, then the engine's) | Why |
|---|---|---|
| In front of the enemy, on the side **opposite the rest of the group** | templar `TEMPLAR`, Ethertech `RIDER` | Enemies hold their attention on the tank, so they turn their back on everyone else. A cleave (a cone in front of the enemy) then hits only the tank |
| In contact, **behind** the enemy | assassin `ASSASSIN`, aède `CHANTER`, gladiator `GLADIATOR` | Melee classes out of the cone, and the assassin's skills that need its back to the enemy |
| At a good distance, never in melee, never in front | spiritualiste `SPIRIT_MASTER`, sorcier `SORCERER`, barde `BARD`, rôdeur `RANGER`, pistolero `GUNNER` | Enemy area attacks; a ranged class in melee is a class being hit |
| At a good distance, **every member within heal range** | clerc `CLERIC` | Its job is to survive and keep the group healthy |

In his diagram: the tank under the enemies, the enemies facing it, the melee just behind the enemies, the ranged further back, the healer off to one side,
and grey circles for the detection radius of the packs that are not part of the fight, which nobody may enter.

## Answered by the guide's author

1. **Distances and sizes come from the data**, the skills' and the server's, not from figures of his.
2. **The aggro rules are the engine's own**, not the general ones on his third page.
3. **The tank does not walk round the enemy in the middle of a fight.** It chooses its side once, as it closes in to engage.
4. **"Behind" is the engine's `isBehind`**: what lets the assassin cast the skills that ask for its back.

## What the data says (step 0)

**Aggro**, over the 57 000 monster templates that have a range (`srange`, `sangle`):

- Range: median 10 m, three quarters at 15 m or less, nine tenths at 20 m or less, 100 m at the extreme. Most common: 10 m, then 20, 8, 7, 15.
- Angle: **34 000 of them state none and see all round (360°)**. Of the rest, 240° is by far the commonest (14 700), then 300°, 270°, 140°. 3 400 have 0° and
  never notice anyone. Inside the cone they notice from the full range; outside it only within a short radius, 4 m (half the range when that is under 8 m).
- A monster ignores a player ten or more levels above it, guards excepted. His third page has that right and the rest approximately.
- So a pack that states a 240° cone has a blind sector of 120° straight behind it, and a pack that states nothing has none: **a zone is read per monster,
  not assumed.**

**Enemy cleaves and area attacks**, over the 3 100 skills monsters actually use, 1 474 of which hit an area of enemies:

- **233 are cones from the caster**, in front of it unless the data says `BACK`: 240° (115 of them), 120° (61), 90°, 180°, 60°. Reach: median 7 m, nine
  tenths 20 m or less.
- 1 224 are circles: median radius 7 m, three quarters 15 m or less, nine tenths 25 to 30 m. Most (792) are centred on the **target**, which is the tank, so
  whoever stands near the tank is in them; 431 are centred on the monster.
- 16 are bolts, a strip in front of the caster.

**Reach**, from the items and from the class skill trees:

- Weapon: bow, harp and cannon 25 m; gun 20; spellbook 15; orb 2.5, staff 2 and keyblade 2, which is to say melee. Swords, maces, daggers 1.5 to 2 m.
- Spells: sorcerer, spirit master, bard and cleric all cast single target skills from 25 m. Ranger skills range from 1 to 30 m with the bow's 25 added on
  top. Rider (Ethertech) 6 to 20 m.
- Healing: single target heals reach 23 to 25 m.
- Skills that ask for the caster to be behind: eight of the assassin's, none of any other class.

**Facing:** a monster turns towards its target on each attack and when it changes target (`SimpleAttackManager`, `NpcController`), so an enemy fighting the
tank does face the tank, which is what the whole scheme rests on.

**Not measured:** how often today's bots swing from the front, or stand in melee range when ranged. That needs the server running, and is the "before" the
later steps are judged against.

## Where the bots stand today

- Followers stand in slots around the leader (`BotGroupManager.formationSpot`), in world directions.
- In a fight, melee and ranged alike walk to the target's own position (`PlayerBotAI.chase`) and stop at weapon range, wherever that is relative to the way
  it faces. Casters stay put if a skill reaches. A healer holds position beside the group and does not close in.
- Nothing reads the enemy's facing, the other packs, or the group's shape. `PositionUtil.isBehind` and `isInFrontOf` exist and are unused by the module.

## Design

**A table of where, and a function of what.** `BotPosition` (authored, like `BotRole`): `FRONT`, `BEHIND`, `RANGED`, `HEALER`. It is not `BotRole`: a chanter and
a bard are both `SUPPORT` there and stand in different places here.

**One mechanism for all four.** Around the group's target (the skull, else the weakest enemy: [group-roles.md](group-roles.md)), lay candidate points on a ring
whose radius is the role's, keep those that satisfy the role's angle, drop the unsafe ones, take the nearest to where the bot stands.

| Role | Radius | Angle, from the enemy's facing | Also |
|---|---|---|---|
| `FRONT` | contact | the side away from the group's centre | chosen once, as it closes in; never reconsidered mid fight |
| `BEHIND` | contact | within 45° of straight behind | inside the engine's `isBehind` (±90°), and inside the free sector of a 240° cone (±60°) |
| `RANGED` | 15 m or its reach less 2 m, whichever is nearer | the half away from the tank | 15 m is the radius three quarters of enemy circles stay under |
| `HEALER` | within 23 m of every member (heal reach less a margin) | the half away from the tank | out of the 15 m too, where the group's spread allows |

"Behind within 45°" is stricter than the engine's ±90° on purpose: a 240° cone leaves only ±60° free, and 240° is the commonest state. Both numbers are
this plan's choice and are revisited with the figures from step 2.

**Safe** means inside no aggro zone of an enemy that is not already fighting the group, read from that monster's own `srange`, `sangle` and short radius,
and the ten level rule for the bot's own level. A pure function over a list of zones, tested with made up numbers.

**Pure first.** The geometry takes a record (enemy position and heading, tank position, group centre, zones, role) and returns a point or nothing, the same
shape as `TemplarPlaybook.decide`: argued in a test, not in a play session.

**Calm.** A bot moves only when it is outside a tolerance of its spot, and recomputes only when the enemy has moved a few metres, as `chase` already does
with `RETARGET_STEP`. Without it every bot dances whenever an enemy takes a step, and every move is a packet ([moving-a-body.md](moving-a-body.md)).
A cast roots a bot, so casters reposition between casts.

**Group fights only.** A solo bot is untouched, as everywhere else.

## Milestones, in verifiable order

0. **Measured**, apart from the "before" figures; see above.
1. **The table and the geometry, no behaviour change.** `BotPosition`, the spot function, the safety function, unit tests for each role on made up coordinates.
2. **Melee behind, ranged back.** `chase` aims at the spot instead of the target for `BEHIND` and `RANGED`. In game: the share of melee swings from behind, the
   distance of the ranged, both against the "before" figures taken first.
3. **The healer.** The heal range band. In game: no member out of its reach, and no healer walking into the fight.
4. **The tank.** The side it engages from is the one opposite the group, chosen as it closes in and kept. In game: the enemy's back to the group in a
   stabilised fight, as in his diagram.
5. **Routes that respect aggro zones.** Until now only the destination is checked; the walk there may still cut through a pack's cone. The path search has no
   cost for ground, so this needs one, and it is the likeliest piece to be larger than it looks. Decided after 2 to 4 have been seen.

## Risks

- **Dancing.** An enemy that follows the tank moves the whole formation. Tolerances and the recompute step are the defence, and they are guesses until step 2.
- **Reactive steering.** Closing in from the far side is the sort of move that gets a bot stuck on a rock ([navigation-prototype.md](navigation-prototype.md)).
- **Packs.** With several enemies on the tank, the anchor is the marked one and the others are wherever they stand. Good enough for a pull of two or three.
- **Walls.** Indoors, the side opposite the group may be stone. A candidate with no ground is dropped, and a role with no candidate stays where it is.
- **The tank still reaches the far side by walking there**, through a pack's cone if that is the way. Until step 5 that is the one place a bot can draw a new
  enemy on its own account.
