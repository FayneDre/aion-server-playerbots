# Group roles

What a bot's role changes about how it fights, and only ever inside a group. Split out of
[combat-skills.md](combat-skills.md), which it was outgrowing.

`BotRole` is the one authored table in the module: the data says what a skill does and never says what a class is *for*. Four roles, not three —
`SUPPORT` exists because a chanter states 197 physical damage against 17 magical, and a class that hits does not belong under `HEALER`.

| Role | Classes | What changes |
|---|---|---|
| `TANK` | templar | Roars whenever it can, saves its single target taunts for a monster hitting a mate, and stays on its feet while the group is engaged rather than sitting to heal. Its own rules: [templar-plan.md](templar-plan.md) |
| `HEALER` | cleric | Weighs the danger a member is in as well as its health, and does not join the attack at all |
| `SUPPORT` | chanter, songweaver | Tends to the group first, then fights |
| `DAMAGE` | everything else | Assists the tank's target before the leader's |

**Only inside a group**, with one exception. A solo bot is untouched: a templar alone does not taunt and still sits down to recover. The templar's *defensives* (Hand of Healing, Empyrean Armor, Iron Skin) are the exception, and hold alone as well, which the guide confirmed.

A taunt is **not** `ProvokerEffect`, which is a proc. This used to be found by the `BOOSTHATE` effect, with no list of ids. It is named by skill group now
(`WA_PROVOKE`, `KN_STUNNINGSNACHER`, `KN_MASSIVEPROVOKE`) in `TemplarPlaybook`, because Capture has no `BOOSTHATE` at all: it pulls and hits, and
The guide counts it as a taunt.
The healer's rule is the one with a number in it: being under attack counts as 20 points of health, which is both a tie-break and a widening. A member
above the healing threshold is healed anyway while something is hitting it, because by the time the cast lands it will be under it. Lowest health alone
always acts one beat late.

## What a healer does with a surplus

A cleric in a group used to heal and nothing else, for a whole instance, whatever its bar said — watched that way through the Fire Temple, damage
"néant". The rule it obeyed is right at low mana and was stated too strongly: a healer's mana belongs to the people it keeps alive, and only the
surplus belongs to the monster. Above 60% it joins the attack; below, it goes back to its own job.

**Healing always comes first.** The order, on both ticks, is the bot itself if it is low, then the member in most danger, and only then anything else:
on the decision tick `tendToTheGroup` runs before `tendTheFight` is even asked, and in the fight loop the first thing `useBestSkill` does for a healer is heal,
ahead of its opening burst, its buffs and every damage skill. The damage is what it does when nobody needs mending, and nothing more.

It is wired in `PlayerBotAI.tendTheFight` on the decision tick: with nobody to heal and the bot in its place, it attacks what the group attacks while it has
the surplus, and `fight` stops it again when the mana is under 60 %, unless the monster is hitting the healer itself. Written down for a long time before it was
actually connected: `hasManaToSpare` existed and nothing called it.

One bug went with it: the rule that ends a fight with a monster nearly dead (20 % health) by skipping every heal skipped the heal of the **tank** too, so a
healer stopped healing exactly when the group's target was about to die. The finishing rule is about the bot's own survival; a healer now heals a hurt
member whatever the target's health.

What has not changed is that it keeps its distance. A healer never chases: out of reach of anything it knows, it holds position beside the group rather
than walking into weapon range, where a cleric is a cleric being hit and silent for the rest of the fight.

## Two monsters, one tank

The tank no longer taunts the target it is already swinging at: the guide keeps Taunt and Capture for peeling, and uses Provoking Roar whenever it is
ready and something is within 8 m. The part a group feels is the monster that got past and is hitting
somebody else — "le templier ne gère pas correctement l'aggro si plus de 1 mob". A tank now also looks for an enemy whose chosen victim is a team mate
and pulls it back.

Whoever it is on, not the worst case: the aggro list says who is most hated, not who can least afford it. And the one it is actually hitting, not one
it merely holds a grudge against — a monster hates everything that has touched it, and answering every grudge would mean taunting things already
looking at the tank.

A templar also never casts Stubborn Spirit or Bodyguard, and in a group no area attack on enemies other than its own roar: see [templar-plan.md](templar-plan.md).

## The mark

A group fights one thing at a time, and it is the tank that chooses it. When monsters are on the group, the tank puts the skull (brand 14, the engine's own
`/Brand`) on the one with the **least health in absolute terms** — the one that dies first, whatever fraction of its bar that is — and every member
that fights follows it: `targetToAssist` reads the mark before anybody's selection, and a bot already swinging at something else moves onto it at its
next attack tick. When the marked one dies the tank marks the next; with nobody left, the skull is taken down, because real players in the group see it.

Only the skull counts, and a skull on a live enemy is never moved, whoever put it there: a leader who marks by hand is obeyed. A mark on something that
is not fighting the group is followed all the same: the guide has a player mark an enemy even out of combat to make it the main target, which is how a
group is told to start. A mark the bot cannot see is left alone rather than taken down. A healer does not follow it, as it does not join the attack at all.

**Without a mark, the target is the same for every class** (the guide's "Classe 101"): the enemy fighting the group, meaning hitting a member or the bot itself,
that has the least health in absolute terms. This replaced the earlier order of the tank's target, then the leader's, then anything fighting the group, so a
leader's selection no longer steers the group; a mark does.
