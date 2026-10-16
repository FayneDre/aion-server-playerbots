<#
.SYNOPSIS
    Runs //bot commands on the running game server without a game client, and prints the answers.

.DESCRIPTION
    Appends each command to bot-commands.txt in the game server's folder, which the server reads every few seconds (BotInbox), then waits for the
    answers to appear in the log and prints them. Only the commands that need no player are taken: see docs/bot-commands.md, section Testing.

.PARAMETER Command
    One or more commands, as typed after //bot. Run in the order given.

.PARAMETER ServerRoot
    The server installation. Defaults to AION_SERVER_HOME.

.PARAMETER TimeoutSeconds
    How long to wait for the answers. Spawning and creating bots takes a few seconds each.

.EXAMPLE
    .\tools\bot-command.ps1 'version'
    .\tools\bot-command.ps1 'squad form Tankbot Healbot Dpsbot', 'squad'
#>
param(
    [Parameter(Mandatory)][string[]]$Command,
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [int]$TimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
if (-not $ServerRoot) { $ServerRoot = [Environment]::GetEnvironmentVariable('AION_SERVER_HOME', 'User') }
if (-not $ServerRoot) { throw 'No server path. Set AION_SERVER_HOME or pass -ServerRoot.' }
$inbox = Join-Path $ServerRoot 'game-server\bot-commands.txt'
$log = Join-Path $ServerRoot 'game-server\log\server_console.log'
if (-not (Test-Path $log)) { throw "No log at $log. Is the server installed there?" }

# Read from where the log ends now, so answers to an earlier run of the same command are not taken for this one's
$start = (Get-Item $log).Length
$utf8 = New-Object System.Text.UTF8Encoding($false) # no byte order mark: the server would read it as part of the first command
[System.IO.File]::AppendAllText($inbox, (($Command -join "`n") + "`n"), $utf8)

function Show-NewAnswers {
    $stream = [System.IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
    try {
        $null = $stream.Seek($start, 'Begin')
        $text = (New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)).ReadToEnd()
    } finally {
        $stream.Dispose()
    }
    $lines = @($text -split "`r?`n" | Where-Object { $_ -match 'BOT INBOX' })
    for ($i = $script:printed; $i -lt $lines.Count; $i++) {
        $line = $lines[$i] -replace '^.*BOT INBOX ', ''
        if ($line.StartsWith('>')) { $script:answered++; Write-Host $line -ForegroundColor Cyan }
        elseif ($line.StartsWith('failed')) { $script:answered++; Write-Host $line -ForegroundColor Red }
        else { Write-Host $line }
    }
    $script:printed = $lines.Count
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$answered = 0
$printed = 0
while ((Get-Date) -lt $deadline -and $answered -lt $Command.Count) {
    Start-Sleep -Milliseconds 500
    Show-NewAnswers
}
# the last command's answer is written just after the command itself
Start-Sleep -Milliseconds 500
Show-NewAnswers
if ($answered -lt $Command.Count) {
    Write-Host "Only $answered of $($Command.Count) command(s) answered within $TimeoutSeconds s. Is the server running a build with the inbox?" -ForegroundColor Yellow
    exit 1
}
