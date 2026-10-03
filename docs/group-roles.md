# Group roles

What a bot's role changes about how it fights, and only ever inside a group. Split out of
[combat-skills.md](combat-skills.md), which it was outgrowing.

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

## What a healer does with a surplus

A cleric in a group used to heal and nothing else, for a whole instance, whatever its bar said — watched that way through the Fire Temple, damage
"néant". The rule it obeyed is right at low mana and was stated too strongly: a healer's mana belongs to the people it keeps alive, and only the
surplus belongs to the monster. Above 60% it joins the attack; below, it goes back to its own job.

What has not changed is that it keeps its distance. A healer never chases: out of reach of anything it knows, it holds position beside the group rather
than walking into weapon range, where a cleric is a cleric being hit and silent for the rest of the fight.

## Two monsters, one tank

Preferring a taunt on the target the tank is already swinging at is half the job. The half a group feels is the monster that got past and is hitting
somebody else — "le templier ne gère pas correctement l'aggro si plus de 1 mob". A tank now also looks for an enemy whose chosen victim is a team mate
and pulls it back.

Whoever it is on, not the worst case: the aggro list says who is most hated, not who can least afford it. And the one it is actually hitting, not one
it merely holds a grudge against — a monster hates everything that has touched it, and answering every grudge would mean taunting things already
looking at the tank.
