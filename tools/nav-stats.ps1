<#
.SYNOPSIS
    Sums up how the bots moved, from the game server's log: fights started and given up for want of a way, routes planned and refused, bots wedged.

.DESCRIPTION
    The figures the open world plan is measured by (docs/openworld-navigation-plan.md, milestone 0). The give-ups are counted per bot and per target as well
    as in total, because a total hides what the first count of them showed: one bot made 404 of 594, going round three monsters it could not reach.

.PARAMETER Since
    Only the lines from this time of day on, as HH:mm or HH:mm:ss, to measure a stretch of play after a restart or a change.

.PARAMETER Top
    How many bots and targets to name. 8 by default.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.EXAMPLE
    .\tools\nav-stats.ps1
    .\tools\nav-stats.ps1 -Since 18:57
#>
param(
    [string]$Since,
    [int]$Top = 8,
    [string]$ServerRoot = $env:AION_SERVER_HOME
)

$ErrorActionPreference = 'Stop'
if (-not $ServerRoot) { throw "No server path. Set the AION_SERVER_HOME environment variable or pass -ServerRoot." }
$log = Join-Path $ServerRoot 'game-server/log/server_console.log'
if (-not (Test-Path $log)) { throw "No server log at $log" }

$lines = Get-Content $log | Where-Object { $_ -match 'com\.aionemu\.gameserver\.playerbot\.\S+ - Bot ' }
if ($Since) {
    # the log's lines start with an ISO date and time; the time of day is compared as text, which orders correctly at a fixed width
    $from = if ($Since.Length -eq 5) { "$($Since):00" } else { $Since }
    $lines = $lines | Where-Object { $_.Length -gt 19 -and $_.Substring(11, 8) -ge $from }
}
if (-not $lines) { Write-Host 'No bot lines in the log for that stretch.' -ForegroundColor Yellow; exit 0 }
$first = $lines[0].Substring(11, 8); $last = $lines[-1].Substring(11, 8)

$kinds = [ordered]@{
    'fights started'          = 'PlayerBotAI - Bot \S+ starts attacking '
    'fights given up'         = 'PlayerBotAI - Bot \S+ cannot reach .+ and gives up'
    'left beyond the camp'    = 'PlayerBotAI - Bot \S+ leaves .+ alone: it is beyond its camp'
    'back to camp after 3'    = 'PlayerBotAI - Bot \S+ gave up on \d+ targets in a row'
    'errands given up'        = 'BotErrands - Bot \S+ cannot reach '
    'day plans given up'      = 'BotDay - Bot \S+ cannot get to '
    'routes planned'          = 'BotMoveController - Bot \S+ has a route of '
    'routes refused'          = 'BotMoveController - Bot \S+ has no route to '
    'arrivals'                = 'PlayerBotAI - Bot \S+ arrived at destination'
    'steps aside'             = 'BotMoveController - Bot \S+ steps aside '
    'no headway'              = 'BotMoveController - Bot \S+ makes no headway '
    'walled in'               = 'BotMoveController - Bot \S+ is walled in '
    'could not move at all'   = 'PlayerBotAI - Bot \S+ could not move at all '
    'deaths'                  = 'PlayerBotAI - Bot \S+ died at '
}
$counts = [ordered]@{}
foreach ($kind in $kinds.Keys) { $counts[$kind] = @($lines | Where-Object { $_ -match $kinds[$kind] }).Count }

$bots = @($lines | Where-Object { $_ -match 'PlayerBotAI - Bot (\S+) spawned' } | ForEach-Object { $Matches[1] } | Sort-Object -Unique).Count
Write-Host "Bot log from $first to $last, $bots bot(s) spawned in it" -ForegroundColor Cyan
foreach ($kind in $counts.Keys) { "  {0,-24} {1,7}" -f $kind, $counts[$kind] }
if ($counts['fights started'] -gt 0) {
    "  {0,-24} {1,6:N0}%" -f 'fights given up / started', (100.0 * $counts['fights given up'] / $counts['fights started'])
}

$giveUps = @($lines | Where-Object { $_ -match 'PlayerBotAI - Bot (\S+) cannot reach (.+) and gives up' } | ForEach-Object { [pscustomobject]@{ Bot = $Matches[1]; Target = $Matches[2] } })
if ($giveUps) {
    Write-Host "Bots that gave up most ($(@($giveUps | Select-Object -ExpandProperty Bot -Unique).Count) gave up at least once)" -ForegroundColor Cyan
    $giveUps | Group-Object Bot | Sort-Object Count -Descending | Select-Object -First $Top | ForEach-Object { "  {0,6}  {1}" -f $_.Count, $_.Name }
    Write-Host 'Bot and target given up on most' -ForegroundColor Cyan
    $giveUps | Group-Object { "$($_.Bot) -> $($_.Target)" } | Sort-Object Count -Descending | Select-Object -First $Top | ForEach-Object { "  {0,6}  {1}" -f $_.Count, $_.Name }
}
