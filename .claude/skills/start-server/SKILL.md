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

## Each server runs as a bare JVM, not through start.bat

The script reads the `JAVA` line out of each server's `start.bat` and launches java itself, so memory settings stay
where the server keeps them while the batch wrapper is skipped.

That wrapper is skipped for one reason: **a batch script cannot be interrupted quietly.** CTRL+C makes cmd ask
whether to terminate the job, and `start.bat` then ends on a `PAUSE` of its own, so its window outlives the server
twice over, waiting for keypresses. Killing it instead does not help — start.bat relaunches itself in Windows
Terminal, which keeps a tab open when its process dies with a non-zero exit code. Without the batch there is no
prompt at all: the console belongs to the JVM and closes when it does.

The cost is start.bat's restart-on-exit-code-2 loop, which a development server does not need. If the `JAVA` line
cannot be found, the script falls back to running start.bat and says so.

## Stopping the server

**Never kill the game server process.** It saves player data (positions, inventories, game time since the last periodic save) from a JVM shutdown hook that only runs on `System.exit` or a console CTRL+C. `Stop-Process` and `taskkill /F` call TerminateProcess, which skips it entirely and silently loses data.

```powershell
.\tools\stop-server.ps1
```

It sends a real CTRL+C (via `tools/send-ctrl-c.ps1`, which must run in its own process because attaching to another console detaches the caller from its own) and waits for the exit. It also closes the console afterwards if a `cmd.exe` is hosting one, which covers a server someone started from start.bat by hand. Shutdown is immediate when only staff is online, otherwise the server announces it and waits `gameserver.shutdown.delay` seconds (120 by default).

`deploy.ps1 -Restart` uses this path, so redeploying never loses data.

## When a server fails to start

The script throws after the timeout and names the server. Its own window stays open with the stack trace. Common causes:

- **`Access denied for user ... (using password: NO)`** — database credentials. The game server's are overridden in `<server>/game-server/config/mygs.properties`, which is intentionally never touched by deploys.
- **Port already in use** — a previous instance is still running; close its window.
- **Handler compile error** — a `.java` file under `data/handlers/` fails to compile at startup. The trace names the file.
