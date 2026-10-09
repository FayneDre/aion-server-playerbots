<#
.SYNOPSIS
    Runs the offline navmesh toolchain against the local server's geo data.

.DESCRIPTION
    The tool reads data/geo directly and starts nothing else, so it runs from the server directory.
    It uses the jar built in this repository and never writes to the server installation: replacing
    a jar under a running JVM breaks it, because classes are loaded lazily.

.PARAMETER MapId
    The map to work on, or "all" to report totals across every map. Several maps can be given, separated by commas, and are generated one after the other.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.EXAMPLE
    .\tools\navmesh.ps1 210010000
    .\tools\navmesh.ps1 all
    .\tools\navmesh.ps1 110010000,400010000   # Sanctum and Reshanta, one after the other
#>
param(
    [string]$MapId,
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$Obelisks
)

$ErrorActionPreference = 'Stop'

if (-not $ServerRoot) {
    throw "No server path. Set the AION_SERVER_HOME environment variable or pass -ServerRoot."
}

$gameServer = Join-Path $ServerRoot 'game-server'
if (-not (Test-Path (Join-Path $gameServer 'data/geo'))) {
    throw "No geo data found under $gameServer"
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem (Join-Path $repoRoot 'game-server\target\game-server-*.jar') |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } |
    Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $jar) { throw "No built jar found. Run: mvn -pl game-server -am package" }

# the generator only needs the game-server classes and the JDK, so no other libraries are on the classpath
Push-Location $gameServer
try {
    if ($Obelisks) {
        # the audit asks the same service the bots do, which logs through slf4j: the server's libraries are needed, the generator's bare jar is not enough
        $arguments = @('obelisks'); if ($MapId) { $arguments += $MapId }
        & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.NavmeshTool @arguments
        exit $LASTEXITCODE
    }
    if (-not $MapId) { throw 'Give a map id, or -Obelisks.' }
    # several maps at once, one after the other: 110010000,400010000
    foreach ($id in $MapId -split ',') {
        Write-Host "Generating the mesh of map $id..." -ForegroundColor Cyan
        & java -Xmx4g -cp $jar.FullName com.aionemu.gameserver.playerbot.navmesh.NavmeshTool $id.Trim()
        if ($LASTEXITCODE -ne 0) { throw "Navmesh tool failed on map $id (exit code $LASTEXITCODE)" }
    }
    Write-Host 'Done. Restart the game server to load the new meshes: .\tools\stop-server.ps1 then .\tools\start-server.ps1' -ForegroundColor Green
} finally {
    Pop-Location
}
