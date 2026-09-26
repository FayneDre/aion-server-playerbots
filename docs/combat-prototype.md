# Combat prototype milestones

First deliverable of the playerbot module: a headless bot `Player` that spawns in the world, is visible to real clients, picks a target, auto-attacks it and casts class skills.

See [playerbot-architecture.md](playerbot-architecture.md) for the module layout and the reasoning behind the core patch.

**Out of scope here:** pathfinding, grouping, auction house, legions, RvR. The bot fights a target within reach and does not chase.

Each milestone is independently demoable, ordered so the riskiest unknown is proven first.

## Status

**M0 to M6 are implemented and verified in game, plus a first autonomy layer.** A bot loads from the database with no connection, spawns visible to real clients, draws its weapon, auto-attacks and casts offensive class skills, fights back when attacked, engages hostiles within reach on its own, and despawns cleanly. Verified against both mobs and a real player in a duel.

Autonomy lives in `PlayerBotAI`: a decision tick every second picks a target through `BotTargetSelector` when idle, and `handleAttack` triggers retaliation. It is on by default and toggled with `//bot auto <name>` — without that switch no behavior can be tested in isolation, since the bot always re-engages.

Bots also move: they walk to a commanded point, chase a target out of weapon reach and return to their anchor, within the bounds described in [navigation-prototype.md](navigation-prototype.md). A bot works a **camp**: a 60 m circle around its anchor. It engages anything within 25 m, walks across the camp towards the nearest mob when its immediate surroundings are clear, breaks off any chase that leaves the camp, and only returns to its anchor when the whole camp is empty. `BotTargetSelector` searches a 25 m radius rather than weapon range, and leaves alone whatever another player or ungrouped bot is already fighting, so several bots in the same spot spread over the mobs instead of piling onto the nearest one. Reading `npc.getTarget()` is not enough for that, since it is only set once the mob retaliates: `BotTargetRegistry` has each bot claim its target atomically before attacking.

What the prototype deliberately does not do yet:

- **Survive deliberately.** It never flees and never uses potions; it sits down where it stands to regenerate (resting multiplies hp regen by 8, so a standing bot takes minutes to heal what a seated one heals in seconds). It picks fights up to 3 levels above itself and only above 90% hp, leaves whatever killed it alone for 2 minutes, and on death resurrects after 10 s at its anchor with 25% hp/mp and soul sickness, like a player picking the obelisk. Those last rules exist because a bot reviving at 25% hp next to the mob that killed it dies again on a loop. Defending itself is never gated: the limits only apply to fights it starts, and an npc currently attacking the bot is picked before anything else, so a bot pulled by several mobs finishes them off instead of sitting down between two kills.
- **Rotate skills per class** (M7, started). It heals itself and casts from whatever range a skill has, but still picks the highest-id usable skill, which is a decent proxy for "strongest available" and not a real rotation. Buffs are still never cast.
- **Anything outside combat besides looting and resting** — no grouping, economy or social behavior.

Looting works like the client's second half only: `CM_START_LOOT` opens the corpse and cancels its decay task, `CM_LOOT_ITEM` takes a line. Bots skip the opening, because `DropService.requestDropItem` does not need an open drop list and a bot that failed to close one would leave the corpse lying around forever. Once the bag is 70% full the bot turns selective and only takes kinah, quest items and anything above ordinary quality: the rest is vendor fodder, and it cannot go and sell yet. After a kill the bot walks within 4 m of the corpse (only the client enforces looting range, so the server would happily let it vacuum from 25 m) and takes every line it is entitled to.

Two lessons worth carrying forward:

1. **Whatever the client normally does by itself must be redone server-side for a bot.** Drawing the weapon was the first example: without the emotion a real client sends, the bot dealt damage with no attack animation. Spawn protection is the second: `CM_MOVE` and `CM_ATTACK` end it, so a bot kept blinking, untargetable **and immune to damage** (`PlayerController.onAttack` returns early) for the whole minute. It is now ended when the bot moves or attacks, exactly where the client would. Expect more of these.
2. **Let the engine validate.** `Skill.useNoAnimationSkill()` already checks mp, cooldown, range, target and restrictions and returns false, so the bot tries candidate skills in order instead of duplicating that logic, which would drift from upstream.

## M0 — Scaffolding and admin command

Create `PlayerBotService` (empty singleton) and `game-server/data/handlers/admincommands/Bot.java` returning a placeholder message. Add `bot = 5` to `game-server/config/administration/commands.properties`.

Admin command classes under `data/handlers/admincommands/` are compiled at runtime and auto-registered, so no core file changes.

**Verify:** in game as GM, `//bot` replies in chat.

## M1 — Load a Player from the DB with no connection

`PlayerBotLoader`: `AccountService.loadAccount(accountId)` then `PlayerService.getPlayer(objId, account)`. Log name, class, level, equipped item count, skill count, max HP, attack speed. **Do not spawn yet.**

**Verify:** `//bot load <objId>` prints sane values (HP > 0, skills > 0). This isolates DB loading from world entry — the riskiest unknown. If it throws here, nothing else matters.

## M2 — Spawn into the world, visible to a real client

`PlayerBotEnterWorldService`: the essential subset of `PlayerEnterWorldService`, with **no `client.sendPacket` calls**.

```text
bot.setPosition(World.createPosition(mapId, x, y, z, heading, instanceId))  // next to the GM
World.storeObject(bot)
World.spawn(bot)
bot.getLifeStats().synchronizeWithMaxStats()
bot.getController().onEnterWorld()        // from CM_LEVEL_READY
activatePassiveSkillEffects(bot)
StigmaService.onPlayerLogin(bot)
AbyssSkillService.updateSkills(bot)
```

Skip `MultiClientingService` (it dereferences the connection's IP/MAC) and the periodic save tasks for now.

**Verify:** `//bot spawn <objId>` — the character appears beside you with the correct model and gear, and is targetable. **This is the key demonstration of the prototype.**

## M3 — Clean despawn

`PlayerBotLeaveWorldService`: `bot.getController().delete()` (the actual world removal), remove effects, cancel scheduled tasks, drop from the registry. Never touch `getClientConnection()`.

**Verify:** spawn/despawn repeatedly with no leak — `World.findVisibleObject(objId) == null` after each despawn.

## M4 — Attach the bot AI

Apply the `Creature.setAi()` patch, then write `PlayerBotAI extends AITemplate<Player>` logging in `handleSpawned`, `handleAttack`, `handleDied`. Attach **before** spawning (this is what `Ai.java` does: despawn → swap → respawn).

**Verify:** spawn the bot and hit it — the console logs `handleAttack`. This proves engine AI events reach a `Player`-typed AI.

## M5 — Auto-attack loop

There is no server-side auto-attack loop for players (the real client re-sends `CM_ATTACK` per swing). Model the loop on `ai/manager/SimpleAttackManager.java`:

```text
if bot dead or not spawned            -> stop
if target invalid or dead             -> reacquire(), else stop
if out of range or no line of sight   -> reschedule in 1s (no chasing: there is no pathfinding)
otherwise:
    bot.getController().attackTarget(target, 0, true)
    reschedule in (attackSpeed + 100) ms
```

`PlayerController.attackTarget` (:399) applies its own server-side attack-speed throttle using `gameStats.getAttackSpeed().getCurrent()`, so never reschedule faster than that.

**Verify:** `//bot attack` on a mob — swing animation, floating damage, the mob's HP drops and its aggro switches to the bot.

## M6 — Skill casting

Filter `bot.getSkillList().getAllSkills()` by `!bot.isSkillDisabled(template)`, sufficient MP, and range, then:

```java
Skill skill = SkillEngine.getInstance().getSkillFor(bot, template, bot.getTarget());
if (skill != null)
    skill.useNoAnimationSkill();
```

`getSkillFor` returns null if the skill is not in the player's skill list, so the bot character must have skills actually learned.

Cooldowns are keyed by `template.getCooldownId()`, **not** by skill id — a frequent source of "the bot spams" or "the bot never re-casts".

**Verify:** the bot casts a visible skill and respects its cooldown.

## M7 — Making the class matter

**Started.** Two gaps were closed first, because both made every class play alike:

- **Healing.** Skill selection only ever considered skills whose target relation is `ENEMY`, so everything aimed at oneself or an ally was filtered out by construction — a priest fought like a warrior and recovered by sitting in the grass. A bot now heals itself below 50% in a fight, and after one when the wound is worth the mana: below 70% health with over 30% mana. Otherwise it rests, which restores both at eight times the rate and is free. Heals are recognised by their effects (`EffectType.HEAL`, `HEALINSTANT`), so this holds for every healing class without a table of skill ids to maintain.
- **Range.** Skills were only ever tried once the target was within **weapon** reach, so a caster walked into melee before casting a spell good from twenty five metres. It now tries to cast before closing, and stands still to do it. Which skills reach how far needs no table either: the engine validates each skill's range as it is cast, so trying is itself the question.

What is left: a real priority order per `PlayerClass` instead of the highest usable id, self buffs (same blind spot healing had — they target the caster), and openers.

## Ending a fight against a player

`canKeepFighting` asked only whether the target was alive and visible. A duel opponent stays both, so bots chased their opponent around the map once the duel ended. The check now asks the engine whether the target is still an enemy (`isEnemy`, which already accounts for duels and PvP zones), for **player** targets only: a player's standing changes mid fight, an npc's does not, and asking it of a peaceful mob would make the bot drop it the moment it swung.

## Bot characters in the database

For the prototype, **reuse a character created manually in game** (second account, leveled and geared via GM commands), referenced by its object id. No new failure surface: the DB row is exactly what `PlayerService.getPlayer` expects (appearance, skills, equipment, life stats, inventory).

Programmatic creation via `PlayerService.newPlayer` / `storeNewPlayer` is the right long-term answer for mass bot populations with a reserved `account_id` range, and stays behind a later `createBot(...)` milestone.

## Known traps

| Trap | Symptom | Fast check |
|---|---|---|
| `isOnline()` used as a validity test | Bot ignored by aggro, chat, visibility | `grep -rn "isOnline()"` and audit the combat path |
| Connection NPE in the world-entry path | Stack trace on `//bot spawn` | Wrap M2 in try/catch and log the full trace |
| Skills not learned | `getSkillFor` returns null, bot only auto-attacks | Log skill count in M1; fix with `SkillLearnService.learnNewSkills` |
| Cooldown keyed by `getCooldownId()` | Bot spams or never re-casts | Log the cooldown each tick |
| No weapon equipped | Damage of 1-3, odd `getAttackType()` | M1 logs equipped item count |
| Attack-speed throttle (`PlayerController:399`) | Bot swings at half rate | Reschedule at `attackSpeed + 100`, never less |
| `PlayerRestrictions.canAttack` / `canUseSkill` blocking | `attackTarget` returns immediately | Call the restriction check yourself and log a `false` result |
| Stats not initialized | Max HP 0, instant death | M1 logs max HP; call `synchronizeWithMaxStats()` in M2 |
| Ghost bots after a crash | Leaked objects and object ids | Shutdown hook draining the registry |

## End-to-end verification

1. `mvn -pl game-server -am package` compiles (Java 25, `sourceDirectory = src`).
2. Deploy the jar to the local server folder, start login-server and game-server.
3. Log in as GM in a low-level mob area.
4. Run `//bot load` → `//bot spawn` → `//bot attack` → `//bot despawn`, checking each milestone visually in the client.
5. Watch the server console: no `NullPointerException`, no uncancelled-task warnings.
