<#
.SYNOPSIS
    Joins on the installed meshes the steps of the data's patrol routes that they cut, by writing the ground those steps walk (data/navmesh/<map>.patrols) and building the meshes again.
.DESCRIPTION
    `NavmeshTool <map> walkers` says which steps of a patrol route no short walk joins; `walkers write` writes a point every half metre along each cut step that stands on the ground, and
    the generator reads those points as ground that was walked (see docs/navigation/verification-tools.md). One pass leaves a few steps cut, as the ground it joined shows more, so
    this writes twice: write, build, write, build with the links. It starts from the installed mesh, which it takes to be current, and keeps the points an earlier run wrote
    (-Fresh forgets them).
    The game server must be stopped (a build takes 2 to 3 GB). About 3 to 4 minutes a map. The .patrols files are data of the server, not of the repository: run this again on a new install.
.PARAMETER Maps
    The maps to treat. Default: every map of tools/walker-routes-baseline.txt with a step cut.
#>
param([int[]]$Maps = @(), [switch]$Fresh)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$serverRoot = if ($env:AION_SERVER_HOME) { $env:AION_SERVER_HOME } else { 'D:\Jeux\Aion\server' }
$gameServer = Join-Path $serverRoot 'game-server'
$running = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*com.aionemu.gameserver.GameServer*' }
if ($running) { throw 'The game server is running. Stop it first: .\tools\stop-server.ps1' }
$jar = Get-ChildItem (Join-Path $gameServer 'libs') -Filter 'game-server-*.jar' | Select-Object -First 1
if (-not $jar) { throw "No game-server jar in $gameServer\libs: deploy first" }
if ($Maps.Count -eq 0) {
    $Maps = @(Get-Content (Join-Path $repo 'tools\walker-routes-baseline.txt') | Where-Object { $_ -match '^\d+ [1-9]\d*$' } | ForEach-Object { [int]($_ -split ' ')[0] })
}
$tool = 'com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool'
Push-Location $gameServer
try {
    foreach ($map in $Maps) {
        Write-Host "Map ${map}: patrol ground" -ForegroundColor Cyan
        # what an earlier run wrote is kept and built on: a step still cut after it is asked again; -Fresh starts from nothing
        if ($Fresh) { Clear-Content "data/navmesh/$map.patrols" -ErrorAction SilentlyContinue }
        & java -Xmx2g -cp "$($jar.FullName);libs/*" $tool $map walkers write | Select-String '^(Map|Wrote)' | ForEach-Object { Write-Host "  $($_.Line)" }
        & java -Xmx4g -cp "$($jar.FullName);libs/*" $tool $map | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Building map $map failed (exit code $LASTEXITCODE)" }
        & java -Xmx2g -cp "$($jar.FullName);libs/*" $tool $map walkers write | Select-String '^(Map|Wrote)' | ForEach-Object { Write-Host "  $($_.Line)" }
        # the last build writes the links again, and checks the routes a tester walked
        & (Join-Path $repo 'tools\navmesh.ps1') $map
        if ($LASTEXITCODE -ne 0) { Write-Host "navmesh.ps1 said a route a tester walked is cut on map $map" -ForegroundColor Red }
        & java -Xmx2g -cp "$($jar.FullName);libs/*" $tool $map walkers | Select-String '^Map ' | ForEach-Object { Write-Host "  now: $($_.Line)" }
    }
} finally { Pop-Location }
