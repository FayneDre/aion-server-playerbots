<#
.SYNOPSIS
    Walks the patrol routes of the data on the installed open-world meshes and fails on a map with more steps cut than tools/walker-routes-baseline.txt allows.
.DESCRIPTION
    Each step of a patrol route (data/static_data/npc_walker) is a place the designers had a creature walk, so a mesh that does not join two steps by a short walk cut something
    a body walks. Run it after navmesh.ps1 and after any change to the generator; the server may be running. See docs/navigation/verification-tools.md.
.PARAMETER Maps
    Check only these maps.
#>
param([int[]]$Maps = @())

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$serverRoot = if ($env:AION_SERVER_HOME) { $env:AION_SERVER_HOME } else { 'D:\Jeux\Aion\server' }
$gameServer = Join-Path $serverRoot 'game-server'
$jar = Get-ChildItem (Join-Path $gameServer 'libs') -Filter 'game-server-*.jar' | Select-Object -First 1
if (-not $jar) { throw "No game-server jar in $gameServer\libs: deploy first" }
$baseline = @{}
Get-Content (Join-Path $repo 'tools\walker-routes-baseline.txt') | Where-Object { $_ -match '^\d+ \d+$' } | ForEach-Object { $p = $_ -split ' '; $baseline[[int]$p[0]] = [int]$p[1] }
$failed = $false
Push-Location $gameServer
try {
    foreach ($map in ($baseline.Keys | Sort-Object)) {
        if ($Maps.Count -gt 0 -and $Maps -notcontains $map) { continue }
        $line = & java -Xmx2500m -cp "$($jar.FullName);libs/*" com.aionemu.gameserver.playerbot.navmesh.tool.NavmeshTool $map walkers | Select-String '^Map \d+: ' | Select-Object -First 1
        if ($line -and $line.Line -match ' (\d+) joined by a short walk, (\d+) not') {
            $cut = [int]$Matches[2]
            $mark = if ($cut -gt $baseline[$map]) { $failed = $true; 'FAIL' } else { 'ok  ' }
            Write-Host ("{0} map {1}: {2} step(s) cut, {3} allowed" -f $mark, $map, $cut, $baseline[$map])
        } else { Write-Host "skip map $map : no answer" }
    }
} finally { Pop-Location }
exit ([int]$failed)
