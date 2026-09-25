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
    Write-Host "Stopping the game server (PID $($gameServerProcess.ProcessId))..." -ForegroundColor Cyan
    Stop-Process -Id $gameServerProcess.ProcessId -Force
    while (Get-NetTCPConnection -State Listen -LocalPort 7777 -ErrorAction SilentlyContinue) {
        Start-Sleep -Milliseconds 300
    }
}

if (-not $SkipBuild) {
    Write-Host 'Building...' -ForegroundColor Cyan
    Push-Location $repoRoot
    try {
        & mvn -q -pl game-server -am package
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

if ($Restart) {
    & (Join-Path $PSScriptRoot 'start-server.ps1') -ServerRoot $ServerRoot
} else {
    Write-Host 'Restart the game server to apply.' -ForegroundColor Yellow
}
