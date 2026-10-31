<#
.SYNOPSIS
    Walks the routes in tools/golden-routes.txt on the installed meshes, both ways, and fails on one that is shut or a detour.
.DESCRIPTION
    A route is a place a tester walked on foot. navmesh.ps1 runs this for the maps it built; run it by hand after any change to the generator or to MapOpenings.
    The server need not be stopped: it only reads the meshes.
.PARAMETER Maps
    Check only these maps.
.EXAMPLE
    .\tools\check-golden-routes.ps1
    .\tools\check-golden-routes.ps1 -Maps 220020000
#>
param([int[]]$Maps = @())

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$serverRoot = if ($env:AION_SERVER_HOME) { $env:AION_SERVER_HOME } else { 'D:\Jeux\Aion\server' }
$gameServer = Join-Path $serverRoot 'game-server'
$jar = Get-ChildItem (Join-Path $gameServer 'libs') -Filter 'game-server-*.jar' | Select-Object -First 1
if (-not $jar) { throw "No game-server jar in $gameServer\libs: deploy first" }
Push-Location $gameServer
try {
    & java -Xmx1500m -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool golden (Join-Path $repo 'tools\golden-routes.txt') @Maps
    exit $LASTEXITCODE
} finally { Pop-Location }
