<#
.SYNOPSIS
    Runs the offline navmesh toolchain against the local server's geo data.

.DESCRIPTION
    The tool reads data/geo directly and starts nothing else, so it runs from the server directory.
    It uses the jar built in this repository and never writes to the server installation: replacing
    a jar under a running JVM breaks it, because classes are loaded lazily.

.PARAMETER MapId
    The map to work on, or "all" to report totals across every map. Several maps can be given, separated by commas or spaces, and are generated one after the other.
    Quoted or not: PowerShell reads an unquoted 110010000,400010000 as a list of two numbers, not as one piece of text, and this takes both.

.PARAMETER Dungeons
    Builds the mesh of every group dungeon listed in tools/dungeon-meshes.txt, and copies each under the ids that share its geometry. With -Reach, checks all of them instead.
    A bot in an instance only positions itself where its map has a mesh, so this is what a server needs once. With ids after it, only those dungeons (and the copies
    that share their geometry) are worked on: .\tools\navmesh.ps1 -Dungeons 300040000,300050000

.PARAMETER Force
    With -Dungeons: builds again the meshes that already exist. Without it they are left alone, since a dungeon takes minutes to build and one that is already good is not
    made better by building it again. Needed after a change to the generator.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.EXAMPLE
    .\tools\navmesh.ps1 210010000
    .\tools\navmesh.ps1 all
    .\tools\navmesh.ps1 -Dungeons            # the 45 geometries of the group dungeons, and their copies; those already built are left alone
    .\tools\navmesh.ps1 -Dungeons -Force     # builds all of them again
    .\tools\navmesh.ps1 110010000,400010000   # Sanctum and Reshanta, one after the other
#>
param(
    [string[]]$MapId,
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$Obelisks,
    [switch]$Reach,
    [switch]$Dungeons,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

# one id per entry, whether they came as a list, as "a,b" or as "a b"
$maps = @($MapId | ForEach-Object { "$_" -split '[,\s]+' } | Where-Object { $_ })

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
        $arguments = @('obelisks'); if ($maps) { $arguments += $maps[0] }
        & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.NavmeshTool @arguments
        exit $LASTEXITCODE
    }
    if ($Dungeons) {
        $groups = Get-Content (Join-Path $PSScriptRoot 'dungeon-meshes.txt') | Where-Object { $_ -match '^\d' } | ForEach-Object { ,@($_ -split '\s+' | Where-Object { $_ }) }
        # with ids given, only the dungeons that have one of them (as the mesh or as a copy of it) are worked on
        if ($maps) { $groups = @($groups | Where-Object { $ids = $_; $maps | Where-Object { $ids -contains $_ } }) }
        if ($Reach) {
            $worst = 0
            foreach ($group in $groups) { foreach ($id in $group) {
                & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.NavmeshTool $id reach
                if ($LASTEXITCODE -gt $worst) { $worst = $LASTEXITCODE }
            } }
            exit $worst
        }
        foreach ($group in $groups) {
            if (-not $Force -and (Test-Path "data/navmesh/$($group[0]).nav")) {
                Write-Host "Map $($group[0]) already has its mesh, left alone (-Force builds it again)." -ForegroundColor DarkGray
            } else {
                Write-Host "Generating the mesh of map $($group[0])..." -ForegroundColor Cyan
                & java -Xmx4g -cp $jar.FullName com.aionemu.gameserver.playerbot.navmesh.NavmeshTool $group[0]
                if ($LASTEXITCODE -ne 0) { throw "Navmesh tool failed on map $($group[0]) (exit code $LASTEXITCODE)" }
            }
            foreach ($copy in $group | Select-Object -Skip 1) {
                if (-not $Force -and (Test-Path "data/navmesh/$copy.nav")) { continue }
                Copy-Item "data/navmesh/$($group[0]).nav" "data/navmesh/$copy.nav" -Force
                Remove-Item "data/navmesh/$copy.glide" -ErrorAction SilentlyContinue
                if (Test-Path "data/navmesh/$($group[0]).glide") { Copy-Item "data/navmesh/$($group[0]).glide" "data/navmesh/$copy.glide" -Force }
                Write-Host "  copied under $copy"
            }
        }
        Write-Host 'Done. Restart the game server to load the new meshes: .\tools\stop-server.ps1 then .\tools\start-server.ps1' -ForegroundColor Green
        exit 0
    }
    if (-not $maps) { throw 'Give a map id, -Dungeons or -Obelisks.' }
    if ($Reach) {
        # an instance: from where the party arrives, can a bot walk to where the monsters are? Exit code 1 when some spots cannot be reached
        $worst = 0
        foreach ($id in $maps) {
            & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.NavmeshTool $id.Trim() reach
            if ($LASTEXITCODE -gt $worst) { $worst = $LASTEXITCODE }
        }
        exit $worst
    }
    # several maps at once, one after the other: 110010000,400010000
    foreach ($id in $maps) {
        Write-Host "Generating the mesh of map $id..." -ForegroundColor Cyan
        & java -Xmx4g -cp $jar.FullName com.aionemu.gameserver.playerbot.navmesh.NavmeshTool $id.Trim()
        if ($LASTEXITCODE -ne 0) { throw "Navmesh tool failed on map $id (exit code $LASTEXITCODE)" }
    }
    Write-Host 'Done. Restart the game server to load the new meshes: .\tools\stop-server.ps1 then .\tools\start-server.ps1' -ForegroundColor Green
} finally {
    Pop-Location
}
