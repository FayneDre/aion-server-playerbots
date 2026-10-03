# How a bot chooses a skill

`BotSkillManager` picks the skills, `PlayerBotAI.useBestSkill` decides which question to ask first. Everything here is read from the skill data rather than from lists of skill ids, because a list is a thing to maintain and the data already knows. For where this sits in the wider plan see [combat-prototype.md](combat-prototype.md).

## The order, in one place

`useBestSkill(target)` is the whole of a bot's in-fight judgement, so it lives in one method rather than being restated wherever a skill might be cast: **approach, opening burst, defensive ability, heal, gap closer, attack.** A cast costs a swing either way, which is why staying alive comes before damage and why nothing is tried twice in the same tick.

Within each of those, `skills(bot, filter)` returns the candidates in the order to try them and `cast` stops at the first that fires. The engine validates mp, cooldown, range, target and every start condition as each one is tried, so trying **is** the question — no table of ranges or requirements to keep in step with upstream.

Mana is reserved as well: a class that can heal stops paying for attack skills below 25% mana, so a fight going badly does not find it unable to afford the heal. For everyone else, mana exists to be spent.

## Upkeep, and abilities kept in hand

A buff and a cooldown are the same kind of data and mean opposite things. Cast everything, and a scout burns its evasion window on an empty field. Cast nothing, and half of every class goes unused.

**The data draws the line by itself**: a buff that lasts at least as long as its own cooldown can be kept up for ever, which is what makes it upkeep. One whose cooldown outlasts it cannot — it is a window a player opens on purpose, and spending it on nothing means not having it later. Measured over every buff a class can learn: **1906 are upkeep, 350 are kept in hand.** No list of skill ids to curate, which is the point.

Those 350 then need a moment. `stance(SkillTemplate)` reads what an ability is *for*, again from the data, in two passes:

1. **By effect type.** A shield, an evasion, a cleanse, a reflector is defensive; a cast-time or attack boost is offensive. Unambiguous, so it decides first.
2. **By the stats it raises**, for the large family of plain stat buffs. Stat names are consistent across all 150-odd of them, so the naming classifies better than a list that would need revisiting every time one is added — `RESIST`, `DEFEN`, `EVASION`, `BLOCK`, `PARRY` against `ATTACK`, `CRITICAL`, `ACCURACY`. `PENETRATION` is tested first, because it reads as resistance while being its opposite.

**Only bonuses are read, never penalties.** A penalty is what a skill costs, not what it is for: Berserking cuts defence by half and accuracy by 200 to buy 80% attack, and counting its penalties classified it as defensive — the exact opposite of what it is. That one rule moved 23 skills to the right side.

The result over those 350: **187 defensive, 64 usable as an opener, 23 offensive but too rare to spend, 76 left alone** because neither pass could label them (procs, mostly: `ProvokerEffect` fires on attack or on being attacked depending on a `hitType` the template does not expose, so which side it is on cannot be read).

When each fires:

| | Trigger |
|---|---|
| Defensive, cooldown ≤ 3 min | Below 70% health in a fight — before the heal, since it prevents damage instead of repairing it, and only works in advance |
| Defensive, cooldown > 3 min | Below 50% health: the rarer the ability, the deeper the trouble it is kept for |
| Offensive, cooldown ≤ 3 min | The fight's first action, **once** — a burst spent on a mob already dying is thrown away, and one that will not fire now is on cooldown, which is its own answer |
| Offensive, cooldown > 3 min | Never. A player keeps these for something that warrants it, and a bot cannot tell that a given mob does |

Three minutes is the one judgement call here, and it is the same number both ways: long enough to matter in the fight it is spent on, short enough to be back before the next one that needs it.

## Class openers

How a fight starts was identical for every class: walk up, swing. Three families in the data fix that, and a fourth was actively hurting.

**No learnable skill carries a `<back>` condition**, so there is nothing to gain from manoeuvring behind a target — the engine does the placement itself, through the `MOVEBEHIND` effect on Ambush, Blind Side and Fangdrop Stab. Positional play needs no pathing, only the right skill.

| Family | Skills | What the bot does |
|---|---|---|
| `DASH`, `MOVEBEHIND` | 58 (Springing Slice, Steam Rush, Ambush, Whirling Strike…) | Preferred over damage **while still closing**: a leap costs the same cast and saves the walk |
| `HIDE`, duration ≥ 10 s | 5 (Stealth, Hide, Shadow Walk, Wind Walk, Cloaking Word) | Cast once per fight while out of range, to walk in unseen. The engine drops it on the first blow |
| `HIDE`, duration < 10 s | 3 (Night Haze, Shadow Illusion) | Left where it was, as a defensive button |
| `BACKDASH` | 25 (Parting Shot, Retreating Slash, Fighting Withdrawal…) | **Never picked as an attack** |

The hide durations split the two uses cleanly with nothing in between: the escapes last two or three seconds, the approach skills twenty and up.

`BACKDASH` was a real bug, not a missing feature. These are ordinary attacks that happen to leap backwards, so a bot choosing skills for damage alone undid its own chase and walked the distance again, over and over. Kiting is a genuine tactic and one a bot may learn later; until then these only work against it.

The approach also had to be taken away from two rules that would have claimed it. `Shadow Walk` lasts five minutes on a three-minute cooldown, which makes it upkeep by the duration rule — bots would have been permanently invisible, which is the opposite of populating a world. It is not a defensive button either. A long hide is its own thing, and the approach has its own once-per-fight flag so that hiding does not also cost the bot its opening burst.

## Picking an attack

Attacks were tried highest id first. Skills are learned in level order, so that reads as "the strongest one available" and is a fair approximation — but it ignored the thing Aion combat is actually built on.

### The strongest, not the newest

Ranking by the highest skill id was meant to read as "the strongest one available", since skills are learned in level order. Against the data it is badly wrong, because the most recently learned skill is not the best one:

| Class | It led with | While this sat unused |
|---|---|---|
| Gladiator | Body Smash, 322 | Sure Strike, **2519** |
| Sorcerer | Soul Freeze, 1055 | Storm Strike, **5292** |
| Cleric | Enfeebling Burst, 255 | Call Lightning, **3190** |

Attacks and heals are now ordered by the number the data states — damage as the percentage of the bot's attack the client shows, healing in points. Every damaging effect in the game states its value as a percentage, so they compare directly; classes are dominated by one damage type each (a chanter has 197 physical against 17 magical, a cleric 175 magical against 12 physical), so the two scales barely meet.

Heals over time are excluded, and by the class hierarchy rather than by a rule of ours: `HealEffect` descends from `HealOverTimeEffect`, not from `AbstractHealEffect`. That is the right answer for the right reason — their value is one tick, not a total, so it was never comparable.

### Chains come first, and not as a preference

**716 of the skills a class can learn continue a chain**, against 727 that open one, and every class has them — 96 for a chanter, 88 for a gladiator, 57 for a cleric. A continuation is a window that closes on its own, and the engine closes it the moment the bot does anything else: `Skill.canUseSkill` resets the chain whenever a non-chain skill passes its checks, and `ChainCondition.validate` resets a chain in progress merely because the bot *tried* another chain's opening link. Asking in the wrong order destroyed the very thing being asked about, so this is a correctness rule and not a matter of taste.

The data marks the first link of a chain with `_1TH` in its category; anything else carrying a chain condition continues one. That is the whole test. Trying continuations first costs nothing when no chain is open: `ChainCondition` returns false and the next candidate is tried.

### A weakening skill is not re-applied

All 157 enemy-targeted `DEBUFF` skills carry a stack group, so a debuff already on the target is recognised the same way a buff already on the bot is. Re-applying one buys nothing and costs the swing that would have gone into damage. Only skills whose *whole purpose* is the debuff are checked — an attack that happens to leave a mark is still worth casting for its damage.

### A friendly skill is not a skill you may cast on a friend

Which heals and buffs can reach a group mate is read from `first_target` in the data, not left to the engine. Aimed at somebody else, a skill marked
`ME` is not refused: `FirstTargetProperty` turns it back on the caster and reports success. Over five thousand skills are marked that way, including
the Bandage Heal every character knows — so every class found a heal that "worked" on its ally, healed itself, and left the ally hurt for ever. Since
tending to the group is checked before fighting, a melee bot beside a wounded player bandaged itself on a loop instead of fighting.

The target relation does not answer this question, and that is what made it invisible for so long: `FRIEND` says who a skill may help, not whether
somebody else can be chosen. Only `TARGET`, `TARGETORME` and `TARGET_MYPARTY_NONVISIBLE` mean it can leave its caster.

## What a role changes

`BotRole` is the one authored table in the module: the data says what a skill does and never says what a class is *for*. Four roles, not three —
`SUPPORT` exists because a chanter states 197 physical damage against 17 magical, and a class that hits does not belong under `HEALER`.

| Role | Classes | What changes |
|---|---|---|
| `TANK` | templar | Prefers a taunt over a stronger attack, and stays on its feet while the group is engaged rather than sitting to heal |
| `HEALER` | cleric | Weighs the danger a member is in as well as its health, and does not join the attack at all |
| `SUPPORT` | chanter, songweaver | Tends to the group first, then fights |
| `DAMAGE` | everything else | Assists the tank's target before the leader's |

**Only inside a group.** A solo bot is untouched: a templar alone does not taunt and still sits down to recover.

A taunt is **not** `ProvokerEffect`, which is a proc. The taunts are among the 24 skills carrying `BOOSTHATE`; the ones aimed at an enemy are the real
article, and the rest — `Winged Strength`, `Reduce Enmity Increase Rate` — are self buffs the existing offensive test already turns down.

The healer's rule is the one with a number in it: being under attack counts as 20 points of health, which is both a tie-break and a widening. A member
above the healing threshold is healed anyway while something is hitting it, because by the time the cast lands it will be under it. Lowest health alone
always acts one beat late.

## Stigmas

Moved to [stigmas.md](stigmas.md).

## Boarding a robot

An aethertech's whole class sits behind one toggle. Every skill it knows carries a `RideRobotCondition` asking `isInRobotMode()`, and its Embark toggle
also grants `ATTACK_RANGE +4000` — four metres. Out of its robot it can cast nothing and reach nothing, which in game was a bot standing five metres
from the monster killing it, target selected, doing nothing at all. It was reported three times before the class turned out to be the common factor.

It never boarded because Embark is a toggle, and toggles were allowed to mantras alone — a rule written when mantras were the only ones anybody had
looked at. The rule it protects still holds, so this asks first whether the robot is already on. Recognised by its `RideRobotEffect` rather than by
name or id, and cast standing still, since Embark states `move_casting allow="false"`.

## A floor under the mana bar

Two separate things used to let a bot end up permanently out of mana.

**It never rested for mana.** The engagement test looked at health alone, so a bot at full health and an empty bar fought on with its auto attack for
ever — and never sat down, which is what pays the eightfold regeneration rate.

**And it emptied the bar on the first monster.** The reserve that stops a bot spending mana applied to healers only, on the reasoning that only a class
with something to pay for later has anything to save for. True of one fight and false of a career: skills are picked strongest first, so "mana exists
to be spent" meant meeting the second monster with nothing. The floor is everyone's now, and below it the fight is finished with the weapon, which
costs nothing.

The resting threshold sits **above** the spending floor on purpose. Were it at or below, a bot would rise from its rest already unable to cast and sit
straight back down.

## Mantras

A chanter's mantras are toggles, and the rule that stops a bot switching its own toggles off had excluded every one of them, so chanters ran none at all. They are now the **only** toggles a bot may touch, and only ever to turn one on, since casting an active toggle turns it off.

Up to three run at once, which is the engine's own limit (`EffectController` ends the oldest past three). The count is taken from the bot's own book by stack group rather than read off the effect controller, whose aura list is private. Highest id first, as elsewhere, so the most recently learned mantras win the three slots.

## Servants

A spirit master is its spirit, so the summon is cast before any buff: it is the one upkeep still there in ten minutes, and the class cannot fight
without it. Only the lasting companion — the rest of the `SUMMON` family puts a totem, a trap or a gate on the ground, each a tactic with a moment
rather than something to call up because the bot happens to know how.

**Cast only while standing still.** A summon has a cast time and a cast roots the caster, so one started mid-stride is cancelled by the next step. With
the decision tick coming round every second this looped for ever — a caster running across a field conjuring nothing, which is how it was reported. The
bot is not *stopped* to cast: a bot halted for a cast that fails for some other reason never gets anywhere again. Every occupation ends in a pause.

Once the servant exists, `BotServantAI` drives it, because **nothing on the server does** — see `docs/engine-traps.md`. Its order is:

```
gone?      master or servant dead, released          -> the tick takes itself off
too far?   beyond the follow distance                -> walk, and nothing else this tick
defend     anything hating the master or the servant -> attack it
assist     what the master is attacking              -> same target
```

Following outranks fighting, or the servant stops for whatever it passes and is released at the fifty metre leash. Defending outranks assisting,
because something already hitting the master is a fact where the master's target is an intention — the same ordering the bot uses for itself.

It walks in a straight line with ground sampling, and does **not** pathfind. A servant never navigates: it goes where its master has just walked, and
the master does the planning. Where that is not enough the engine's own leash releases it and the bot calls up another at its next pause.

## Procs, and what is left unlabelled

A proc arms something that fires later, so its own effects say nothing about which way it points. What decides is the blow that sets it off: `ProvokerEffect` installs an observer on the bearer's **attacks** for `NMLATK` and `BACKATK`, and on the bearer being **hit** for everything else. That single line is mirrored rather than guessed at, which took 29 more abilities off the unlabelled pile — 20 offensive, 9 defensive. It needed one accessor in the engine, `EffectTemplate.getHitType()`, over a field that was already there.

53 abilities are still left alone, and deliberately. They are travel and utility rather than combat: movement and flight speed, scouting sight, flight-point heals, escape teleports. A bot has no behaviour these would serve, and guessing a side for them would only make it spend a cooldown on nothing.

## What is still missing

An authored rotation per `PlayerClass` — a named opener into a named chain into a named finisher, skills conditional on the fight's state, mana cost weighed against damage. Everything here is read from the data instead, which is why it holds for all fifteen classes at once and needs no table to keep in step with upstream. An authored rotation would beat it for the classes someone sits down and writes, and rot for the rest; it is worth doing once bots have been watched fighting long enough to say which classes actually play badly.
