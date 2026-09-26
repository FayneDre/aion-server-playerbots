<#
.SYNOPSIS
    Runs the offline navmesh toolchain against the local server's geo data.

.DESCRIPTION
    The tool reads data/geo directly and starts nothing else, so it must run from the server
    directory. It uses the deployed jar, so deploy first if you changed the generator.

.PARAMETER MapId
    The map to work on, or "all" to report totals across every map.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.EXAMPLE
    .\tools\navmesh.ps1 210010000
    .\tools\navmesh.ps1 all
#>
param(
    [Parameter(Mandatory = $true)][string]$MapId,
    [string]$ServerRoot = $env:AION_SERVER_HOME
)

$ErrorActionPreference = 'Stop'

if (-not $ServerRoot) {
    throw "No server path. Set the AION_SERVER_HOME environment variable or pass -ServerRoot."
}

$gameServer = Join-Path $ServerRoot 'game-server'
if (-not (Test-Path (Join-Path $gameServer 'data/geo'))) {
    throw "No geo data found under $gameServer"
}

Push-Location $gameServer
try {
    & java -cp "libs/*" com.aionemu.gameserver.playerbot.navmesh.NavmeshTool $MapId
    if ($LASTEXITCODE -ne 0) { throw "Navmesh tool failed (exit code $LASTEXITCODE)" }
} finally {
    Pop-Location
}
