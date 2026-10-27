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

.PARAMETER Field
    For open-world maps: pairs of monsters a hunting bot would pick one after the other, and how many can be walked between. .\tools\navmesh.ps1 210020000,220030000 -Field

.PARAMETER Glides
    Writes the glide links of the maps given, alone: the mesh is not built again, and the server reads them on its next start. For the open world, where the
    generator does not write them. .\tools\navmesh.ps1 210030000 -Glides

.PARAMETER OpenWorld
    Builds again every open-world mesh the server already has (every .nav but the dungeons'), then writes again the glide links of the maps that had some
    and the flight links of the maps that had some, since both are read off the ground the mesh describes. What a server needs after a change to the
    generator, as on 18 October (erosion per surface, so that routes keep a body's width from walls): about 25 minutes, with the game server stopped.

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
    .\tools\navmesh.ps1 -OpenWorld           # every open-world mesh again, with its glide and flight links
    .\tools\navmesh.ps1 110010000,400010000   # Sanctum and Reshanta, one after the other
#>
param(
    [string[]]$MapId,
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$Obelisks,
    [switch]$Reach,
    [switch]$Field,
    [switch]$Glides,
    [switch]$Dungeons,
    [switch]$OpenWorld,
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
    if ($OpenWorld) {
        # the flight links are written from the mesh and read by the server as it starts: none of it is done under a running game server
        $running = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like '*com.aionemu.gameserver.GameServer*' }
        if ($running) { throw 'The game server is running. Stop it first: .\tools\stop-server.ps1' }
        $ids = Get-ChildItem 'data/navmesh' -Filter '*.nav' | Where-Object { $_.BaseName -notlike '3*' } | ForEach-Object { $_.BaseName } | Sort-Object
        if (-not $ids) { throw 'No open-world mesh on this server to build again. Generate them one by one: .\tools\navmesh.ps1 <mapId>' }
        foreach ($id in $ids) {
            # Which links the map has is read before the mesh is built, not after: writing a mesh clears an open-world map's .glide (NavmeshWriter writes
            # links for instances only, and an empty list removes the file), so asking afterwards found none, and the glides of every map that had them
            # were lost to the rebuild of 18 October without a word.
            $kinds = [System.Collections.Generic.List[string[]]]::new()
            foreach ($links in @(@('glide', 'glides'), @('flight', 'flights'), @('ride', 'rides'))) {
                if (Test-Path "data/navmesh/$id.$($links[0])") { $kinds.Add($links) }
            }
            Write-Host "Generating the mesh of map $id..." -ForegroundColor Cyan
            & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $id
            if ($LASTEXITCODE -ne 0) { throw "Navmesh tool failed on map $id (exit code $LASTEXITCODE)" }
            foreach ($links in $kinds) {
                Write-Host "  writing its $($links[1]) again" -ForegroundColor DarkGray
                & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $id $links[1]
                if ($LASTEXITCODE -ne 0) { throw "Writing the $($links[1]) of map $id failed (exit code $LASTEXITCODE)" }
            }
        }
        Write-Host "Done: $($ids.Count) maps. Start the server: .\tools\start-server.ps1 (the first start rebuilds the bots' places, several minutes)" -ForegroundColor Green
        exit 0
    }
    if ($Obelisks) {
        # the audit asks the same service the bots do, which logs through slf4j: the server's libraries are needed, the generator's bare jar is not enough
        $arguments = @('obelisks'); if ($maps) { $arguments += $maps[0] }
        & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool @arguments
        exit $LASTEXITCODE
    }
    if ($Dungeons) {
        $groups = Get-Content (Join-Path $PSScriptRoot 'dungeon-meshes.txt') | Where-Object { $_ -match '^\d' } | ForEach-Object { ,@($_ -split '\s+' | Where-Object { $_ }) }
        # with ids given, only the dungeons that have one of them (as the mesh or as a copy of it) are worked on
        if ($maps) { $groups = @($groups | Where-Object { $ids = $_; $maps | Where-Object { $ids -contains $_ } }) }
        if ($Reach) {
            $worst = 0
            foreach ($group in $groups) { foreach ($id in $group) {
                & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $id reach
                if ($LASTEXITCODE -gt $worst) { $worst = $LASTEXITCODE }
            } }
            exit $worst
        }
        foreach ($group in $groups) {
            if (-not $Force -and (Test-Path "data/navmesh/$($group[0]).nav")) {
                Write-Host "Map $($group[0]) already has its mesh, left alone (-Force builds it again)." -ForegroundColor DarkGray
            } else {
                Write-Host "Generating the mesh of map $($group[0])..." -ForegroundColor Cyan
                & java -Xmx4g -cp $jar.FullName com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $group[0]
                if ($LASTEXITCODE -ne 0) { throw "Navmesh tool failed on map $($group[0]) (exit code $LASTEXITCODE)" }
            }
            foreach ($copy in $group | Select-Object -Skip 1) {
                # a copy older than its source is the mesh of an earlier build: the Illuminary Obelisk's kept glide links from before they were fixed, and measured nothing
                $copied = Get-Item "data/navmesh/$copy.nav" -ErrorAction SilentlyContinue
                if (-not $Force -and $copied -and $copied.LastWriteTime -ge (Get-Item "data/navmesh/$($group[0]).nav").LastWriteTime) { continue }
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
    if ($Glides) {
        # writes each map's .glide alone, the .nav untouched: the server reads it on its next start, so this is run map by map, on purpose
        foreach ($id in $maps) {
            & java -Xmx3g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $id.Trim() glides
            if ($LASTEXITCODE -ne 0) { throw "Writing the glide links of map $id failed (exit code $LASTEXITCODE)" }
        }
        Write-Host 'Done. Restart the game server to load them: .\tools\stop-server.ps1 then .\tools\start-server.ps1' -ForegroundColor Green
        exit 0
    }
    if ($Field) {
        # the open world: from where a monster stands, can a hunting bot walk to the monsters it would pick next? Exit code 1 when some pairs cannot be
        $worst = 0
        foreach ($id in $maps) {
            & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $id.Trim() fieldreach
            if ($LASTEXITCODE -gt $worst) { $worst = $LASTEXITCODE }
        }
        exit $worst
    }
    if ($Reach) {
        # an instance: from where the party arrives, can a bot walk to where the monsters are? Exit code 1 when some spots cannot be reached
        $worst = 0
        foreach ($id in $maps) {
            & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $id.Trim() reach
            if ($LASTEXITCODE -gt $worst) { $worst = $LASTEXITCODE }
        }
        exit $worst
    }
    # several maps at once, one after the other: 110010000,400010000
    foreach ($id in $maps) {
        Write-Host "Generating the mesh of map $id..." -ForegroundColor Cyan
        & java -Xmx4g -cp $jar.FullName com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $id.Trim()
        if ($LASTEXITCODE -ne 0) { throw "Navmesh tool failed on map $id (exit code $LASTEXITCODE)" }
        # links name the mesh's stretches by number, and a new mesh numbers them afresh: a link file left from the old one would join the wrong ground
        foreach ($links in @(@('glide', 'glides'), @('flight', 'flights'), @('ride', 'rides'))) {
            if (Test-Path "data/navmesh/$($id.Trim()).$($links[0])") {
                Write-Host "  writing its $($links[1]) again" -ForegroundColor DarkGray
                & java -Xmx4g -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $id.Trim() $links[1]
                if ($LASTEXITCODE -ne 0) { throw "Writing the $($links[1]) of map $id failed (exit code $LASTEXITCODE)" }
            }
        }
    }
    Write-Host 'Done. Restart the game server to load the new meshes: .\tools\stop-server.ps1 then .\tools\start-server.ps1' -ForegroundColor Green
} finally {
    Pop-Location
}
