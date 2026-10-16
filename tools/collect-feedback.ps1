<#
.SYNOPSIS
    Cuts the minutes around every //bot note out of a game server log, and zips them with the server's revision.

.DESCRIPTION
    A tester types //bot note the moment something looks wrong, which writes a "BOT NOTE #n" line to the log. This finds every one, keeps the log from
    a few minutes before it to a little after, and writes one excerpt per note plus a summary into a zip small enough to send. The log of a three hour
    session is around twenty megabytes, of which the notes' minutes are a few hundred kilobytes.

    Works on a log copied from another machine: point -Log at it.

.PARAMETER Log
    The log to read. Defaults to the game server's console log under AION_SERVER_HOME.

.PARAMETER Before
    Minutes kept before each note. Default 2: most faults are seen a little after they start.

.PARAMETER After
    Minutes kept after each note. Default 1.

.PARAMETER Out
    The zip to write. Defaults to feedback-<date>.zip in the current folder.

.EXAMPLE
    .\tools\collect-feedback.ps1
    .\tools\collect-feedback.ps1 -Log C:\Users\tester\Downloads\server_console.log -Before 5
#>
param(
    [string]$Log,
    [int]$Before = 2,
    [int]$After = 1,
    [string]$Out
)

$ErrorActionPreference = 'Stop'

if (-not $Log) {
    $serverRoot = $env:AION_SERVER_HOME
    if (-not $serverRoot) { $serverRoot = [Environment]::GetEnvironmentVariable('AION_SERVER_HOME', 'User') }
    if (-not $serverRoot) { throw 'No log given and AION_SERVER_HOME is not set. Pass -Log.' }
    $Log = Join-Path $serverRoot 'game-server\log\server_console.log'
}
if (-not (Test-Path $Log)) { throw "Not found: $Log" }
$Log = (Resolve-Path $Log).Path
if (-not $Out) { $Out = Join-Path (Get-Location) ("feedback-{0:yyyy-MM-dd-HHmm}.zip" -f (Get-Date)) }

# Every line the server writes starts with an ISO time; a line that does not (a stack trace) belongs to the one before it.
$timePattern = '^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})'
function Get-LineTime([string]$line) {
    if ($line -match $timePattern) { return [datetime]::ParseExact($Matches[1], 'yyyy-MM-ddTHH:mm:ss', $null) }
    return $null
}

# First pass: the notes and the revision. A note is its header line; the lines describing its bots follow it and fall inside its window anyway.
$notes = @()
$revision = $null
foreach ($line in [System.IO.File]::ReadLines($Log)) {
    if ($line -match 'BOT NOTE #(\d+) "') {
        $notes += [pscustomobject]@{ Number = [int]$Matches[1]; Time = (Get-LineTime $line); Line = $line }
    } elseif ($line -match 'game-server-[^ ]+\.jar (revision .*)$') {
        $revision = $Matches[1]
    }
}
if ($notes.Count -eq 0) {
    Write-Host "No //bot note in $Log. Nothing to collect." -ForegroundColor Yellow
    exit 1
}

# Second pass: each line goes to the excerpt of every note whose window holds it. One pass over the log whatever the number of notes.
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("feedback-" + [guid]::NewGuid())
New-Item -ItemType Directory $work | Out-Null
try {
    $writers = @{}
    foreach ($note in $notes) {
        $note | Add-Member Start ($note.Time.AddMinutes(-$Before))
        $note | Add-Member End ($note.Time.AddMinutes($After))
        $writers[$note.Number] = New-Object System.IO.StreamWriter((Join-Path $work ("note-{0:D3}.log" -f $note.Number)), $false, (New-Object System.Text.UTF8Encoding($false)))
    }
    $current = $null
    foreach ($line in [System.IO.File]::ReadLines($Log)) {
        $time = Get-LineTime $line
        if ($time) { $current = $time }
        if (-not $current) { continue }
        foreach ($note in $notes) {
            if ($current -ge $note.Start -and $current -le $note.End) { $writers[$note.Number].WriteLine($line) }
        }
    }
    $writers.Values | ForEach-Object { $_.Dispose() }

    $summary = @("Log: $Log", "Server: $(if ($revision) { $revision } else { 'revision not found in this log' })",
        "Window: $Before min before each note, $After after", '', 'Notes:')
    $summary += $notes | ForEach-Object { "  #$($_.Number) at $($_.Time.ToString('yyyy-MM-dd HH:mm:ss')): $($_.Line -replace '^.*BOT NOTE #\d+ ', '')" }
    Set-Content -Path (Join-Path $work 'summary.txt') -Value $summary -Encoding UTF8

    if (Test-Path $Out) { Remove-Item $Out }
    Compress-Archive -Path (Join-Path $work '*') -DestinationPath $Out
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}

$size = [math]::Round((Get-Item $Out).Length / 1KB)
Write-Host "$($notes.Count) note(s) collected into $Out ($size KB)." -ForegroundColor Green
$notes | ForEach-Object { Write-Host "  #$($_.Number) $($_.Time.ToString('HH:mm:ss'))" }
