<#
.SYNOPSIS
    Sums up how the bots moved, from the game server's log: fights started and given up for want of a way, routes planned and refused, bots wedged.

.DESCRIPTION
    The figures the open world plan is measured by (docs/navigation/openworld-navigation-plan.md, milestone 0). The give-ups are counted per bot and per target as well
    as in total, because a total hides what the first count of them showed: one bot made 404 of 594, going round three monsters it could not reach.
    Steps aside and rescue teleports are broken down the same way (docs/navigation/obstacle-steering-plan.md, milestone 0): per minute, by kind of leg, by
    length, by bot and by 20 m square, and the teleports by whether a real player saw them.

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
    'routes with a glide'     = 'BotMoveController - Bot \S+ plans \d+ glide'
    'arrivals'                = 'PlayerBotAI - Bot \S+ arrived at destination'
    'steps aside'             = 'BotMoveController - Bot \S+ steps aside '
    'no headway'              = 'BotMoveController - Bot \S+ makes no headway '
    'walled in'               = 'BotMoveController - Bot \S+ is walled in '
    # rescues and deaths were logged by PlayerBotAI until 27 October 2026, by BotRescue and BotDeath since: both, so older logs still read
    'could not move at all'   = '(PlayerBotAI|BotRescue) - Bot \S+ could not move at all '
    'rescue teleports'        = '(PlayerBotAI|BotRescue) - Bot \S+ .*(is put back home|is put on walkable ground)'
    'deaths'                  = '(PlayerBotAI|BotDeath) - Bot \S+ died at '
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

# Steps aside and rescue teleports, the figures docs/navigation/obstacle-steering-plan.md is judged by
$minutes = [math]::Max(1.0, ([timespan]::Parse($last) - [timespan]::Parse($first)).TotalMinutes)
$steps = @($lines | Where-Object { $_ -match 'BotMoveController - Bot (\S+) steps aside at (\S+) (\S+) \S+ to (\S+) (\S+) \S+, heading for [^(]+(\((.+)\))?$' } | ForEach-Object {
    # the server writes its decimals in the machine's locale, which here puts a comma where the point goes
    $number = { param($text) [double]::Parse($text.Replace(',', '.'), [cultureinfo]::InvariantCulture) }
    $fromX = & $number $Matches[2]; $fromY = & $number $Matches[3]; $toX = & $number $Matches[4]; $toY = & $number $Matches[5]
    [pscustomobject]@{
        Bot = $Matches[1]
        Kind = if ($Matches[7]) { $Matches[7] } else { 'untagged' }
        Length = [math]::Sqrt(($toX - $fromX) * ($toX - $fromX) + ($toY - $fromY) * ($toY - $fromY))
        Square = '{0}-{1} {2}-{3}' -f ([math]::Floor($fromX / 20) * 20), ([math]::Floor($fromX / 20) * 20 + 20), ([math]::Floor($fromY / 20) * 20), ([math]::Floor($fromY / 20) * 20 + 20)
    }
})
if ($steps) {
    Write-Host ("Steps aside: {0}, {1:N1} a minute, by {2} bot(s)" -f $steps.Count, ($steps.Count / $minutes), @($steps | Select-Object -ExpandProperty Bot -Unique).Count) -ForegroundColor Cyan
    "  by kind of leg"
    $steps | Group-Object Kind | Sort-Object Count -Descending | ForEach-Object { "  {0,6}  {1}" -f $_.Count, $_.Name }
    "  by length"
    "  {0,6}  under 2 m" -f @($steps | Where-Object { $_.Length -lt 2 }).Count
    "  {0,6}  2 to 4 m" -f @($steps | Where-Object { $_.Length -ge 2 -and $_.Length -lt 4 }).Count
    "  {0,6}  4 m and more" -f @($steps | Where-Object { $_.Length -ge 4 }).Count
    "  bots that stepped aside most"
    $steps | Group-Object Bot | Sort-Object Count -Descending | Select-Object -First $Top | ForEach-Object { "  {0,6}  {1}" -f $_.Count, $_.Name }
    "  20 m squares they cluster in"
    $steps | Group-Object Square | Sort-Object Count -Descending | Select-Object -First $Top | ForEach-Object { "  {0,6}  {1}" -f $_.Count, $_.Name }
}
$rescues = @($lines | Where-Object { $_ -match $kinds['rescue teleports'] })
if ($rescues) {
    $seen = @($rescues | Where-Object { $_ -match '\(seen by a player\)' }).Count
    $onItself = @($rescues | Where-Object { $_ -match 'which is where it already was' }).Count
    Write-Host ("Rescue teleports: {0}, {1} seen by a player, {2} onto where the bot already was" -f $rescues.Count, $seen, $onItself) -ForegroundColor Cyan
}
