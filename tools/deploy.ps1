<#
.SYNOPSIS
    Builds the game, login and chat server modules and deploys them to a local server installation.

.DESCRIPTION
    Copies the artifacts the servers actually load:
      - the compiled jars (game-server + commons) into <server>/game-server/libs
      - the handler sources (data/handlers) which the server compiles at startup
      - the chat server's jar into <server>/chat-server/libs, when it changed
      - the libraries the poms ask for, and the login server's jar and commons for the login and chat servers (sync-install.ps1 -LibsOnly: nothing is
        resolved while the poms are as at the last sync; a login or chat server that is running is stopped for it with -Restart, else left as it is)
    Config files are never touched, so local settings (mygs.properties, credentials) survive a deploy. After an upstream merge that changed the data,
    the configuration, sync-install.ps1 -From does those once (see docs/architecture/history/upstream-merge-plan.md); the database is apply-sql.ps1, below.

.PARAMETER ServerRoot
    Root of the server installation, containing the game-server/login-server/chat-server folders.
    Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER SkipBuild
    Deploy the existing build output without running Maven again.

.PARAMETER SkipSql
    Do not apply the statements of update.sql the database has not had yet (apply-sql.ps1 does it after the build, from a backup, with the game server stopped).

.PARAMETER Restart
    Stop the running game server before deploying and start the whole stack again afterwards.
    Without it, deploying while the server runs is refused, because the JVM locks the jar.

.EXAMPLE
    .\tools\deploy.ps1
    .\tools\deploy.ps1 -SkipBuild
    .\tools\deploy.ps1 -Restart
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$SkipBuild,
    [switch]$Restart,
    [switch]$SkipSql
)

$ErrorActionPreference = 'Stop'

if (-not $ServerRoot) {
    throw "No server path. Set the AION_SERVER_HOME environment variable or pass -ServerRoot."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$targetGameServer = Join-Path $ServerRoot 'game-server'

if (-not (Test-Path $targetGameServer)) {
    throw "Not found: $targetGameServer"
}

# The running JVM locks game-server-*.jar, so the copy below would fail halfway through the deploy
$gameServerProcess = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*com.aionemu.gameserver.GameServer*' }

if ($gameServerProcess) {
    if (-not $Restart) {
        throw "The game server is running (PID $($gameServerProcess.ProcessId)). Stop it first, or re-run with -Restart."
    }
    # never kill it: the JVM shutdown hook is what saves player data
    & (Join-Path $PSScriptRoot 'stop-server.ps1')
}

# Every //bot command has to be in docs/bot-commands.md. Checked here rather than left to discipline, because it was not kept: //bot number was
# added and the doc was not touched. A hook on the editor only fires when a file is edited through the editor, and plenty of changes are not.
# Run as its own process rather than called in place: the check ends with exit, which would take this script down with it.
$undocumented = & powershell -NoProfile -File (Join-Path $PSScriptRoot 'check-bot-docs.ps1')
if ($LASTEXITCODE -ne 0) {
    throw "docs/bot-commands.md does not document these //bot command(s): $($undocumented -join ', ')"
}

# Two doc comments in a row mean one of them describes nothing -- see the script for what that cost.
$orphans = & powershell -NoProfile -File (Join-Path $PSScriptRoot 'check-orphan-javadoc.ps1')
if ($LASTEXITCODE -ne 0) {
    throw "Orphaned javadoc (a doc comment directly followed by another): $($orphans -join ', ')"
}

# The docs' links, index, sizes and plan statuses; printed and never blocking, since a stale doc is no reason to keep a fix off the server.
$docProblems = & powershell -NoProfile -File (Join-Path $PSScriptRoot 'check-docs.ps1')
if ($docProblems) {
    Write-Host 'Docs (tools/check-docs.ps1):' -ForegroundColor Yellow
    $docProblems | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
}

# The database is asked now, before a build of minutes: MySQL not started, or a password that changed, is found out here and not after the tests.
# Only looks (no -Apply); the statements are run after the build.
if (-not $SkipSql) {
    $sqlLook = & (Join-Path $PSScriptRoot 'apply-sql.ps1') -ServerRoot $ServerRoot
    $sqlLook | Where-Object { $_ -match ', [1-9][0-9]* not applied yet' } | ForEach-Object { Write-Host $_ -ForegroundColor Yellow }
}

if (-not $SkipBuild) {
    # Allowed, since most deploys are a change being tried; but the jar's revision will end in -DIRTY, which no one can check out, so a build meant for a
    # tester is made from a clean tree and tagged (tools/tag-patch.ps1).
    # From a clean tree the build starts from nothing: Maven repackages no jar when no source changed, so a deploy right after committing what was
    # tried kept the -DIRTY jar of the try, twice, and //bot version named a revision nobody can check out. A dirty tree is a try: kept incremental.
    $phases = @('package')
    if (git -C $repoRoot status --porcelain) {
        Write-Host 'Uncommitted changes: this build will be revision -DIRTY. Commit first if it is going to a tester.' -ForegroundColor Yellow
    } else {
        $phases = @('clean', 'package')
    }
    Write-Host 'Building...' -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        # Tests run here and nowhere else. The root pom sets maven.test.skip so a plain `mvn package` stays fast, which is what upstream wants and
        # what every other build in this repo relies on -- but a test nothing runs is a test that rots, and the population director's two worst
        # faults of 2026-10-04 were each a ten line assertion over a pure function. Overridden for the one build whose output reaches a server.
        # quoted: PowerShell splits a bare -Dmaven.test.skip=false on the dots and hands Maven ".test.skip=false" as a lifecycle phase
        & mvn -q -pl game-server,login-server,chat-server -am @phases '-Dmaven.test.skip=false'
        if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

# The schema changes the database has not had yet, once each, from a backup. Only with something new does it do anything, and then the game server
# must be stopped, which a deploy has asked of it since the start (-Restart stops it; without, it refuses). A failing statement stops the deploy:
# a server started on a half migrated schema fails in ways that are harder to read.
if (-not $SkipSql) {
    Write-Host 'Checking the database...' -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'apply-sql.ps1') -ServerRoot $ServerRoot -Apply
}

# The libraries the poms ask for, and the jars the login and chat servers load from their own folders. Moving to Netty 4.2 on 28 October left an
# install that did not start: deploy.ps1 carried two jars and the rest was a copy made once. Nothing happens while the poms are as at the last sync.
Write-Host 'Checking libraries...' -ForegroundColor Cyan
& (Join-Path $PSScriptRoot 'sync-install.ps1') -LibsOnly -Apply -StopRunning:$Restart -ServerRoot $ServerRoot

Write-Host 'Deploying jars...' -ForegroundColor Cyan
$libs = Join-Path $targetGameServer 'libs'
foreach ($pattern in @('game-server\target\game-server-*.jar', 'commons\target\commons-*.jar')) {
    # -sources/-javadoc jars are build by-products and must never land on the server classpath
    $jar = Get-ChildItem (Join-Path $repoRoot $pattern) |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
        Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $jar) { throw "No jar found for pattern $pattern" }
    Copy-Item $jar.FullName $libs -Force
    Write-Host "  $($jar.Name)"
}

Write-Host 'Deploying handlers...' -ForegroundColor Cyan
$handlersSource = Join-Path $repoRoot 'game-server\data\handlers'
$handlersTarget = Join-Path $targetGameServer 'data'
Copy-Item $handlersSource $handlersTarget -Recurse -Force
Write-Host '  data/handlers'

Write-Host "Deployed to $targetGameServer" -ForegroundColor Green

# The chat server carries the bots' channel lines (CM_BOT_MESSAGE) since 24 October, so it is built and deployed too, but only when its jar changed:
# most deploys touch the game server alone. It holds nothing to save, so stopping it is safe, and a running game server reconnects to it by itself.
$chatJar = Get-ChildItem (Join-Path $repoRoot 'chat-server\target\chat-server-*.jar') -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Sort-Object LastWriteTime | Select-Object -Last 1
$chatLibs = Join-Path $ServerRoot 'chat-server\libs'
if ($chatJar -and (Test-Path $chatLibs)) {
    $installedChatJar = Join-Path $chatLibs $chatJar.Name
    $changed = -not (Test-Path $installedChatJar) -or
        (Get-FileHash $chatJar.FullName).Hash -ne (Get-FileHash $installedChatJar).Hash
    if ($changed) {
        $chatProcess = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like '*com.aionemu.chatserver.ChatServer*' }
        if ($chatProcess -and -not $Restart) {
            Write-Host "Chat server jar changed but the chat server is running: not deployed. Re-run with -Restart." -ForegroundColor Yellow
        } else {
            if ($chatProcess) {
                Stop-Process -Id $chatProcess.ProcessId -Confirm:$false
                Wait-Process -Id $chatProcess.ProcessId -Timeout 30 -ErrorAction SilentlyContinue
            }
            Copy-Item $chatJar.FullName $chatLibs -Force
            Write-Host "  chat-server: $($chatJar.Name)"
        }
    }
}

# start.bat is deliberately not deployed: it carries the installation's own memory settings, and overwriting it would reset them on every deploy. But a
# file nothing deploys is also a file nobody looks at, and that has a cost -- the installed game server ran on -Xmx8192m for days while the repo said
# 2560m, which is how a 2.4 GB heap grew to 3.4 GB on a machine that runs the game client too, with a live set of 960 MB the whole time.
# Reported and not refused, unlike the //bot doc check above: a local override is a legitimate thing to want. Going unnoticed is not.
Write-Host 'Checking JVM options...' -ForegroundColor Cyan
$javaLine = '^\s*JAVA\s+(.*?)\s+-cp'
foreach ($module in @('game-server', 'login-server', 'chat-server')) {
    $installedBat = Join-Path $ServerRoot "$module\start.bat"
    $repoBat = Join-Path $repoRoot "$module\dist\start.bat"
    if (-not (Test-Path $installedBat) -or -not (Test-Path $repoBat)) { continue }
    $installedMatch = Select-String -Path $installedBat -Pattern $javaLine | Select-Object -First 1
    $repoMatch = Select-String -Path $repoBat -Pattern $javaLine | Select-Object -First 1
    if (-not $installedMatch -or -not $repoMatch) {
        Write-Host "  $module : no JAVA line to compare" -ForegroundColor Yellow
        continue
    }
    $installedOpts = $installedMatch.Matches[0].Groups[1].Value
    $repoOpts = $repoMatch.Matches[0].Groups[1].Value
    if ($installedOpts -eq $repoOpts) {
        Write-Host "  $module matches the repo"
    } else {
        Write-Host "  $module start.bat differs from the repo, and no deploy will ever reconcile them:" -ForegroundColor Yellow
        Write-Host "    installed: $installedOpts" -ForegroundColor Yellow
        Write-Host "    repo:      $repoOpts" -ForegroundColor Yellow
    }
}

if ($Restart) {
    & (Join-Path $PSScriptRoot 'start-server.ps1') -ServerRoot $ServerRoot
} else {
    Write-Host 'Restart the game server to apply.' -ForegroundColor Yellow
}
