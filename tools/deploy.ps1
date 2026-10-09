<#
.SYNOPSIS
    Builds the game-server module and deploys it to a local server installation.

.DESCRIPTION
    Copies the two artifacts the server actually loads:
      - the compiled jars (game-server + commons) into <server>/game-server/libs
      - the handler sources (data/handlers) which the server compiles at startup
    Config files are never touched, so local settings (mygs.properties, credentials) survive a deploy.

.PARAMETER ServerRoot
    Root of the server installation, containing the game-server/login-server/chat-server folders.
    Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER SkipBuild
    Deploy the existing build output without running Maven again.

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
    [switch]$Restart
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

if (-not $SkipBuild) {
    Write-Host 'Building...' -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        # Tests run here and nowhere else. The root pom sets maven.test.skip so a plain `mvn package` stays fast, which is what upstream wants and
        # what every other build in this repo relies on -- but a test nothing runs is a test that rots, and the population director's two worst
        # faults of 2026-10-04 were each a ten line assertion over a pure function. Overridden for the one build whose output reaches a server.
        # quoted: PowerShell splits a bare -Dmaven.test.skip=false on the dots and hands Maven ".test.skip=false" as a lifecycle phase
        & mvn -q -pl game-server -am package '-Dmaven.test.skip=false'
        if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
}

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
