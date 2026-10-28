# Plan: bots in a cohort

Status: not started.

Not started; analysed on 19 October. Asked by the testers ("cohorts for the bots"). A cohort is the engine's alliance (`PlayerAlliance`,
`TeamType`): up to four groups of six (`PlayerAllianceGroup`), a captain and vice captains, and leagues of alliances above it. A group of bots works
([group-roles.md](../../combat/group-roles.md), [group-positioning-plan.md](../../combat/history/group-positioning-plan.md)); nothing of it was written or watched for an alliance.

## What stands today

- **Joining**: a bot accepts an alliance invitation like a group's (`BotGroupManager.acceptPendingInvite`, `STR_FORCE_DO_YOU_ACCEPT_INVITATION`),
  from anyone. Nothing ever invites one on purpose, and `BotGroupManager` never dissolves an alliance (line 57: "not ours to dissolve").
- **Leaving**: `BotInstances` takes a bot out of an alliance as out of a group when its player has gone from an instance for five minutes.
- **Everything else reads `bot.getCurrentTeam().getMembers()`**, which for an alliance is all 24, not the bot's own six:
  - **whom it follows**: `BotGroupManager.leaderToFollow` is the alliance's captain, even when the bot's own group is led by another player;
  - **where it stands**: `formationSpot` spreads the followers round that captain, up to 23 on one circle;
  - **positioning in a fight**: `BotStations` counts the whole alliance;
  - **whom it heals and assists**: `BotGroupManager` and the healer's choice look over every member, and `ChanterPlaybook` keeps its upkeep on all
    of them, while in the game an area heal or a mantra reaches **one's own group only**;
  - **packs it must not wake**: `BotSkillManager` asks of every member, which is right and only costs more.
- **Recruiting**: `BotRecruiter` fills a group offer in the Find Group window, never an alliance one (line 109: "an alliance ... not the recruiter's").
- **Marks and roles** are per team: four tanks in an alliance would each think they are the tank.

## Design

**A bot in an alliance acts as a member of its own group of six**, and of the alliance only where that is what the game does:

- one helper, `BotTeams.ownGroup(bot)`: the bot's `PlayerAllianceGroup` in an alliance, its team otherwise. Every reading above that means "my
  group" goes through it; the few that mean "everybody on my side" (not waking packs, not attacking an ally's target) keep the whole team;
- **the leader to follow**: the first real player of one's own group, else its leader, else the captain; a bot whose group holds no player follows
  the captain, which is what a group of bots in someone's alliance should do;
- **the tank of a group** is the tank of its six; the main target comes from the captain's mark when there is one, as a group's comes from its leader.

## Milestones, in verifiable order

### 0. Measure (an afternoon)

A player leading an alliance of two groups: their own with two bots, and a second of five bots (`//bot squad` cannot make an alliance; invitations by
hand, or a `//bot alliance` command if that is quicker). With `//bot debug combat` on a field fight, note whom each bot follows, where it stands, whom
the healers heal, who tanks. This is the measure every change below is checked against.

### 1. One's own group (a day)

`BotTeams.ownGroup`, then following, formation, positioning, healing, assisting and the chanter's upkeep through it; the role and the mark per group.
A test for each reading, on teams made in memory as `BotGroupManager`'s tests do. **Verified** by the measure again: each bot round its own
group's player or the captain, at most five on a circle, healers on their own six.

### 2. Following into an alliance instance (half a day)

`BotInstances` follows a grouped bot in and out; with the leader rule above, a bot follows the player of its own group, so check the entry and
the exit of an alliance instance, and the five minute wait when that player logs out.

### 3. Recruiting a cohort (a day)

`BotRecruiter` filling an alliance offer of the Find Group window, if the window gives one for the content the testers want: each group of the
alliance completed as a group is today (a tank, a healer, a support first), counting the players already in it.

### 4. Leagues

Not planned. They are what sieges use and belong with RvR.

## Open questions

1. **What content they are for**, answered from `instance_cooltimes.xml` (more than six players): the PvE raids Abyssal Splinter and its unstable
   version, Dragon Lord's Refuge and its anguished version, Drakenspire Depths and Infinity Shard (12), the Eternal Bastion (24), Padmarashka's Cave
   (48); the rest is PvP (Kamar, Iron Wall Warfront, Stonespear Reach, the two arenas). So cohorts are PvE content and not only RvR: milestone 2 is
   checked on Dragon Lord's Refuge or the Abyssal Splinter, which have meshes, and milestone 3 is needed.
2. Whether a bot may **lead** an alliance group when no player is in it. The plan says no: the captain leads, as `ChangeLeaderEvent` already keeps a
   bot from becoming leader.
