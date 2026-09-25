---
name: deploy-server
description: Build the game-server module and deploy it to the local Aion server installation. Use whenever code under game-server/src or a handler under game-server/data/handlers changed and the change needs to be tested in game, or when the user asks to deploy, build and deploy, or push changes to their server.
---

# Deploy to the local Aion server

Run from the repository root:

```powershell
.\tools\deploy.ps1
```

Use `-SkipBuild` to deploy the existing build output without recompiling.

The script resolves the server location from the `AION_SERVER_HOME` environment variable. If it is not set, pass `-ServerRoot <path>`.

## What it deploys, and why it matters

The server loads two kinds of files, and they deploy differently. Forgetting the second one is the classic mistake: the jar is up to date but the command still behaves like the old version.

| What | Source | How it reaches the server |
|---|---|---|
| Module code | `game-server/src/` | Compiled into the jar, copied to `<server>/game-server/libs/` |
| Handlers (admin commands, AI scripts, quests) | `game-server/data/handlers/` | Copied as `.java` files, compiled by the server at startup |
| Config | `game-server/config/` | **Never copied** — local settings must survive deploys |

Config is deliberately excluded so the installation's local overrides (`config/mygs.properties`, DB credentials) are never overwritten.

## After deploying

The server must be restarted to pick up the change — both for jar code and for handlers, since handlers are compiled at startup. Use the `start-server` skill.

## Verifying a handler before deploying

Handlers are not part of the Maven build, so a syntax error in one only surfaces at server startup. To catch it early:

```powershell
javac -nowarn -cp "game-server\target\classes;commons\target\classes" -d <tempDir> game-server\data\handlers\admincommands\<Name>.java
```
