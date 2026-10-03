<#
.SYNOPSIS
    Stops the game server gracefully, so player data is saved.

.DESCRIPTION
    The server saves everything from a JVM shutdown hook (ShutdownHook.java), which only runs on System.exit or a
    console CTRL+C. Killing the process instead (Stop-Process / taskkill /F) skips it entirely and loses character
    positions, inventories and game time since the last periodic save.

    This asks by writing <server>/game-server/shutdown.request, which ShutdownRequestWatcher picks up within half a
    second and turns into the same System.exit every other shutdown uses. A real CTRL+C is kept as a fallback for a
    server built before that watcher existed: it works, but only about half the time, because the console it has to
    be sent through is shared with the cmd.exe running start.bat and is not ours to attach to reliably.

    Shutdown is immediate when only staff is online, otherwise the server announces it and waits
    (gameserver.shutdown.delay).

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
    [string]$ServerRoot = $env:AION_SERVER_HOME,
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

<#
    Asks the server to stop, and reports which way the request went out so a failure says where to look.
    The file is preferred: the server owns the check and logs what it saw, where a console event is delivered
    through a console we do not own and fails silently when it is not.
#>
$requested = $false
if ($ServerRoot) {
    $requestFile = Join-Path $ServerRoot 'game-server\shutdown.request'
    try {
        New-Item -ItemType File -Path $requestFile -Force | Out-Null
        Write-Host "  asked through $requestFile" -ForegroundColor DarkGray
        $requested = $true
    } catch {
        Write-Host "  could not write $requestFile ($($_.Exception.Message))" -ForegroundColor Yellow
    }
}

# Both are sent when the file could not be written, and only then: a server that honours the file is already on its
# way out, and a CTRL+C would also reach the cmd.exe sharing its console and leave a prompt behind.
if (-not $requested) {
    $helper = Join-Path $PSScriptRoot 'send-ctrl-c.ps1'
    $sender = Start-Process -FilePath 'powershell.exe' -PassThru -Wait -WindowStyle Hidden `
        -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$helper`"", '-TargetPid', $process.ProcessId
    if ($sender.ExitCode -ne 0) {
        throw "Could not ask PID $($process.ProcessId) to stop (CTRL+C exit code $($sender.ExitCode)). Shut the server down from its own window instead."
    }
    Write-Host '  asked with CTRL+C (no server root given, so no request file)' -ForegroundColor DarkGray
}

# The watcher deletes the file as soon as it sees it, so the file still being there is the one honest sign that
# nothing is listening -- a server built before the watcher existed. Ten seconds is twenty times its poll interval.
$askedAt = Get-Date
$fallbackAfter = $askedAt.AddSeconds(10)
$deadline = $askedAt.AddSeconds($TimeoutSeconds)
$fellBack = $false

while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue)) {
        Write-Host 'Game server stopped and saved.' -ForegroundColor Green
        Close-HostShell $hostShellPid
        exit 0
    }
    if ($requested -and -not $fellBack -and (Get-Date) -gt $fallbackAfter -and (Test-Path $requestFile)) {
        Write-Host '  the server has not picked the file up, falling back to CTRL+C' -ForegroundColor Yellow
        $fellBack = $true
        $helper = Join-Path $PSScriptRoot 'send-ctrl-c.ps1'
        Start-Process -FilePath 'powershell.exe' -Wait -WindowStyle Hidden `
            -ArgumentList '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$helper`"", '-TargetPid', $process.ProcessId | Out-Null
    }
    Start-Sleep -Milliseconds 500
}

if ($Force) {
    Write-Host "Still running after ${TimeoutSeconds}s, killing it (data may be lost)." -ForegroundColor Red
    Stop-Process -Id $process.ProcessId -Force
    exit 0
}
throw "Game server did not stop within $TimeoutSeconds seconds. Check its window, or re-run with -Force."
