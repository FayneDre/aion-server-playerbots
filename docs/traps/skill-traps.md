# Skill traps

What the skill engine decides about a buff or an area that a bot asking the obvious question gets wrong. The rest of the engine's traps are in
[engine-traps.md](engine-traps.md).

## Two buffs are the same buff when their effect ids collide, whatever their stack groups say

**The stack group is not how the engine decides a buff is already up.** `EffectController.isConflicting` compares the *effect ids* of each template,
within a target slot, and ends the weaker of two that collide — equal levels meaning the newcomer wins. The stack group never enters into it. So a bot
that asks "is this buff up?" by stack group, which is the obvious way and the right way for telling two ranks of one skill apart, will believe a buff is
missing that the engine considers present.

**Where the data gives one effect two names, that belief costs an infinite loop.** The bot casts the buff; the engine ends the one already holding that
effect id; the bot now finds *that* one missing and casts it back. Neither is ever up, and nothing in the log says so, because no one logs a buff.

Both halves of it are in the live 4.8 data:

| Skills | Stack groups | Shared effect |
|---|---|---|
| Bard, `Mvt. 2: Summer` / `Mvt. 3: Autumn` | `BA_SONGOFBRAVE` / `BA_SONGOFFIRM` | `statup` 944261 |
| Ranger, `Transformation: White Tiger` / `Krall` | `RA_LIGHT_WHITETIGER` / `RA_LIGHT_KRALLSCOUT` | `shapechange` 175 |

**A buff that cannot win its slot loops just as hard.** The ranger's Mau transformation carries the same `shapechange` 175 at `basiclvl` 160 against the
others' 100, and `isConflicting` refuses a newcomer whose level is lower. With Mau up, White Tiger is refused for ever — so it never lands, never reads
as up, and is recast for ever.

**Upkeep rules read from cooldown versus duration will adopt transformations.** Those four polymorphs last 120 s on a 10 s cooldown, which is the exact
signature of a buff worth keeping up. They have to be excluded by what they do — `EffectType.SHAPECHANGE` — not by how long they last.

## Two buffs are one buff when they share a cooldown id

The third way, after the effect id and the stack group: `EffectController.checkEffectCooldownId` keeps **one effect per cooldown id** in a slot and ends
the older, except for a few ids it lists (1, which is none, 6, 273 and 353). A sorcerer's Robe of Flame and Robe of Cold share cooldown id 1564, under two
stack groups and with no effect id in common, so each check above said the other robe was missing. The bot cast one robe, which ended the other, then cast
the other ten seconds later: a robe every cooldown for 100 to 200 mana each, reported by a tester as a sorcerer that never had mana. `BotSkillManager.isAlreadyUp`
asks this as well now. Other class buffs sharing an id: a cleric's `PR_HEALERSHAND` and `PR_OFFENSIVEMODE`, a bard's two playing styles, a sorcerer's
`WI_ARCANEBOOST` and `WI_ARCANEPOWER`.

## An area centred on the caster always passes the range check

The engine checks a skill's range against its first target, and an area with `first_target="ME"` has the caster as its first target: the check passes
from any distance, and the skill goes off on empty ground. Every one of the gladiator's fifteen areas is of that kind, and a gladiator walking up fired them
all before anything was in reach. `BotSkillManager.areaIsWorthIt` measures the target against the area the way `TargetRangeProperty.checkRange` does
(radius, cone, strip); `wakesABystander`, asked of every cast in `cast`, refuses an area that would take in a monster nobody is fighting yet, alone or
in a group.

## Ready again at once is not the same as not on

A buff with a one second cooldown, as the ranger's Strong Shots and Aiming have, is ready every second for the whole of its minute. A rule that picks "the first of
these that is ready" casts the one that is already on, every second, until something else changes the answer; seen in the log as 35 casts of one buff in eight
minutes, with the unit test passing because it took the buff out of the ready set. Ask whether it is on as well as whether it is ready, and test with both.


## One step under a cast fails it at its end

A skill whose data says `<move_casting allow="false" />` is checked as it ends, not as it starts: `Skill.useSkill` attaches a `StartMovingListener`,
any `onMove()` of the caster sets it, and `PlayerMovedCondition` then fails the cast in `endCast`, time spent and nothing landed. Nothing tells the
caster why. For a bot the step can come from its own walk on the movement thread, a moment after the decision to cast: ending the walk after the cast
began, then before it, then only within the engine's range rule, each left some steps landing under casts. What holds is the step itself refusing to
move a body that is casting (`BotMoveController.moveToDestination`). Measured with a probe on `PlayerController.cancelCurrentSkill` in a squad: casts
lost went from 28 of 162 to 6 of 178, the six all to a target that died first.

## A spell laid on the ground needs a point

A skill whose first target is `POINT` (a sorcerer's Flaming Meteor and Summon Rift, a gladiator's Battle Banner, the Ice Sheet stigma) takes its caster as
its first target and the area's centre from a point the client sends with the cast (`CM_CASTSPELL`, target type 1). Cast without one, the point is
the map's origin. `BotSkillManager.cast` gives it the feet of whom it is aimed at, the bot's own for one laid round itself, and asks the reach itself,
since the engine measures none for these.
