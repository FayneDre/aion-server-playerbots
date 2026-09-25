---
name: start-server
description: Start the local Aion server stack (chat, login, game) in the correct order, waiting for each one to be ready. Use when the user asks to start, restart, relaunch or boot their server, or after deploying a change that needs a restart to take effect.
---

# Start the local Aion server stack

Run from the repository root:

```powershell
.\tools\start-server.ps1
```

Use `-SkipChat` to start without the chat server (in-game chat channels will not work).

The script resolves the server location from the `AION_SERVER_HOME` environment variable. If it is not set, pass `-ServerRoot <path>`.

## Why the order matters

The game server connects to both the chat server and the login server while starting, so they must already be listening. Starting them in parallel, or with a fixed sleep, produces intermittent startup failures.

| Server | Port waited on | Role of that port |
|---|---|---|
| chat-server | 9021 | game server connections |
| login-server | 9014 | game server connections |
| game-server | 7777 | game clients |

The script starts each one, then polls until its port accepts connections before moving on. A server already listening is left alone rather than started twice.

## When a server fails to start

The script throws after the timeout and names the server. Its own window stays open with the stack trace. Common causes:

- **`Access denied for user ... (using password: NO)`** — database credentials. The game server's are overridden in `<server>/game-server/config/mygs.properties`, which is intentionally never touched by deploys.
- **Port already in use** — a previous instance is still running; close its window.
- **Handler compile error** — a `.java` file under `data/handlers/` fails to compile at startup. The trace names the file.
