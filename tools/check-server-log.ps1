<#
.SYNOPSIS
    Reads the game server's console log and says whether the server is healthy.

.DESCRIPTION
    Written because two faults reached a running server and were found only when somebody happened to look at the log: a decision tick that threw
    four thousand times, and a server that had written nothing for fifty minutes because its console had been clicked into. Both are visible in the
    log in seconds, so this looks for them:

      errors      ERROR lines and stack traces, grouped by the first line of the message, with how many of each
      silence     how long since the log was last written; a running server that is quiet is stuck, not idle
      population  how many bots entered the world, which is the one number that says the bot system started at all

    Exits 1 when there are errors or the log has gone silent, 0 otherwise, so a script can act on it.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER WaitSeconds
    How long to wait before reading, counted from the log's first line. Faults tend to show a little after startup, once the bots begin to act; the
    two that prompted this appeared within twenty seconds of the server opening.

.PARAMETER SilentSeconds
    How long a quiet log is tolerated before it counts as stuck.

.EXAMPLE
    .\tools\check-server-log.ps1
    .\tools\check-server-log.ps1 -WaitSeconds 0
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [int]$WaitSeconds = 45,
    [int]$SilentSeconds = 120
)

$ErrorActionPreference = 'Stop'

if (-not $ServerRoot) {
    throw "No server path. Set the AION_SERVER_HOME environment variable or pass -ServerRoot."
}
$log = Join-Path $ServerRoot 'game-server\log\server_console.log'
if (-not (Test-Path $log)) {
    throw "No log at $log"
}

if ($WaitSeconds -gt 0) {
    Write-Host "Waiting $WaitSeconds s for the server to settle before reading its log..." -ForegroundColor DarkGray
    Start-Sleep -Seconds $WaitSeconds
}

$lines = Get-Content $log
$problems = 0

$errors = $lines | Where-Object { $_ -match ' ERROR ' }
if ($errors) {
    $problems++
    Write-Host "$($errors.Count) ERROR line(s):" -ForegroundColor Red
    $errors | ForEach-Object { ($_ -replace '^\S+ ERROR \[[^\]]*\] ', '') -replace '\b[A-Z][a-z]+(?= failed| could not| has)', 'X' } |
        Group-Object { $_.Substring(0, [Math]::Min(120, $_.Length)) } | Sort-Object Count -Descending | Select-Object -First 6 |
        ForEach-Object { Write-Host ("  {0,5} x {1}" -f $_.Count, $_.Name) -ForegroundColor Red }
    $exception = $lines | Where-Object { $_ -match '^[\w.]+(Exception|Error):' } | Group-Object | Sort-Object Count -Descending | Select-Object -First 1
    if ($exception) {
        Write-Host ("  most common exception ({0}x): {1}" -f $exception.Count, $exception.Name) -ForegroundColor Red
    }
} else {
    Write-Host 'No ERROR lines.' -ForegroundColor Green
}

$silent = (Get-Date) - (Get-Item $log).LastWriteTime
if ($silent.TotalSeconds -gt $SilentSeconds) {
    $problems++
    Write-Host ("The log has been silent for {0:N0} minutes. A running server is never that quiet: its console may be paused (it freezes when clicked into), so click it and press Enter." -f $silent.TotalMinutes) -ForegroundColor Red
} else {
    Write-Host ("Log written {0:N0} s ago." -f $silent.TotalSeconds) -ForegroundColor Green
}

$bots = ($lines | Where-Object { $_ -match 'PlayerBotAI - Bot \S+ spawned' }).Count
$warns = ($lines | Where-Object { $_ -match ' WARN ' }).Count
Write-Host "$bots bot(s) entered the world, $warns warning(s)." -ForegroundColor $(if ($bots -gt 0) { 'Green' } else { 'Yellow' })
if ($bots -eq 0) {
    Write-Host '  none: fine if bots are switched off, a fault if they are not.' -ForegroundColor Yellow
}

exit $(if ($problems) { 1 } else { 0 })
