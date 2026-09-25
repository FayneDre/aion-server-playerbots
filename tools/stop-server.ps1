<#
.SYNOPSIS
    Stops the game server gracefully, so player data is saved.

.DESCRIPTION
    The server saves everything from a JVM shutdown hook (ShutdownHook.java), which only runs on System.exit or a
    console CTRL+C. Killing the process instead (Stop-Process / taskkill /F) skips it entirely and loses character
    positions, inventories and game time since the last periodic save.

    This sends a real CTRL+C, then waits for the process to exit. Shutdown is immediate when only staff is online,
    otherwise the server announces it and waits (gameserver.shutdown.delay, 120s by default).

.PARAMETER TimeoutSeconds
    How long to wait for the server to exit before giving up. Must exceed the configured shutdown delay.

.PARAMETER Force
    Kill the process if the graceful shutdown times out. Data saved by the hook up to that point is kept, the rest is lost.

.EXAMPLE
    .\tools\stop-server.ps1
#>
param(
    [int]$TimeoutSeconds = 180,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$process = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*com.aionemu.gameserver.GameServer*' }

if (-not $process) {
    Write-Host 'Game server is not running.' -ForegroundColor Yellow
    exit 0
}

Write-Host "Shutting down the game server gracefully (PID $($process.ProcessId))..." -ForegroundColor Cyan
$helper = Join-Path $PSScriptRoot 'send-ctrl-c.ps1'
$sender = Start-Process -FilePath 'powershell.exe' -PassThru -Wait -WindowStyle Hidden `
    -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$helper`"", '-TargetPid', $process.ProcessId

if ($sender.ExitCode -ne 0) {
    throw "Could not send CTRL+C to PID $($process.ProcessId) (exit code $($sender.ExitCode)). Shut the server down from its own window instead."
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue)) {
        Write-Host 'Game server stopped and saved.' -ForegroundColor Green
        exit 0
    }
    Start-Sleep -Milliseconds 500
}

if ($Force) {
    Write-Host "Still running after ${TimeoutSeconds}s, killing it (data may be lost)." -ForegroundColor Red
    Stop-Process -Id $process.ProcessId -Force
    exit 0
}
throw "Game server did not stop within $TimeoutSeconds seconds. Check its window, or re-run with -Force."
