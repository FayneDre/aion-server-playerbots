<#
.SYNOPSIS
    Stops the game server gracefully, so player data is saved.

.DESCRIPTION
    The server saves everything from a JVM shutdown hook (ShutdownHook.java), which only runs on System.exit or a
    console CTRL+C. Killing the process instead (Stop-Process / taskkill /F) skips it entirely and loses character
    positions, inventories and game time since the last periodic save.

    This sends a real CTRL+C, then waits for the process to exit. Shutdown is immediate when only staff is online,
    otherwise the server announces it and waits (gameserver.shutdown.delay, 120s by default).

    Once the server has exited, the console window start.bat runs in is closed too. It would otherwise sit there
    waiting for a keypress, first on cmd's "terminate batch job?" prompt and then on the PAUSE that ends the
    script. Nothing is killed to achieve this: the JVM has already gone and saved by then.

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

<#
    Closes the console start.bat runs in, which outlives the server twice over: CTRL+C makes cmd ask whether to
    terminate the batch job, and answering it only gets the script as far as its own PAUSE at the end. Both wait
    for a keypress nobody is there to give, so the window lingers until someone closes it by hand.

    Only ever called once the JVM has exited and saved, so this disposes of a shell sitting at a prompt and
    nothing else. It is checked to still be a cmd.exe first, since process ids are reused.
#>
function Close-HostShell([int]$ShellPid) {
    if (-not $ShellPid) {
        return
    }
    $shell = Get-CimInstance Win32_Process -Filter "ProcessId = $ShellPid" -ErrorAction SilentlyContinue
    if ($shell -and $shell.Name -eq 'cmd.exe') {
        Stop-Process -Id $ShellPid -Force -ErrorAction SilentlyContinue
    }
}

$process = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like '*com.aionemu.gameserver.GameServer*' }

if (-not $process) {
    Write-Host 'Game server is not running.' -ForegroundColor Yellow
    exit 0
}

# Noted before the shutdown, because once the JVM is gone nothing links it to the shell that started it any more.
$hostShellPid = $process.ParentProcessId

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
        Close-HostShell $hostShellPid
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
