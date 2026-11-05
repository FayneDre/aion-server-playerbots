<#
.SYNOPSIS
    Starts the local Aion server stack in dependency order, each server in its own window.

.DESCRIPTION
    Order matters: the game server connects to both the chat server and the login server on startup,
    so they must already be listening. Each step waits for the next server's port to actually accept
    connections instead of sleeping for a fixed duration. The stack is then ready only once the game server has
    registered with the login server, which happens minutes after its port opens.

      chat-server   port 9021 (game server connections)
      login-server  port 9014 (game server connections)
      game-server   port 7777 (game clients)

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER SkipChat
    Do not start the chat server (in-game chat channels will not work).

.PARAMETER SkipCheck
    Do not read the game server log after the stack is up (see check-server-log.ps1).

.PARAMETER CheckSeconds
    How long after startup the log check waits before reading.

.PARAMETER TimeoutSeconds
    How long to wait for each server to start listening before giving up.

.PARAMETER RegisterTimeoutSeconds
    How long to wait, once the game port is open, for the game server to register with the login server. Until it has, no client can connect.

.EXAMPLE
    .\tools\start-server.ps1
    .\tools\start-server.ps1 -SkipChat
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$SkipChat,
    [switch]$SkipCheck,
    [int]$CheckSeconds = 45,
    [int]$TimeoutSeconds = 120,
    [int]$RegisterTimeoutSeconds = 600
)

$ErrorActionPreference = 'Stop'

if (-not $ServerRoot) {
    throw "No server path. Set the AION_SERVER_HOME environment variable or pass -ServerRoot."
}

# Asks the system's table first, then connects. On 5 November Get-NetTCPConnection returned nothing at all on this machine while every port was
# open: the script took the running chat server for stopped, started a second one, and stopped there when that one failed on the busy port.
function Test-PortListening([int]$Port) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
        return $true
    }
    $client = New-Object Net.Sockets.TcpClient
    try {
        return $client.ConnectAsync('127.0.0.1', $Port).Wait(1000) -and $client.Connected
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

<#
    Reads the java command out of start.bat, so the memory settings and classpath stay wherever the server keeps
    them rather than being restated here.
#>
function Get-JavaArguments([string]$Script) {
    foreach ($line in Get-Content $Script) {
        if ($line -match '^\s*JAVA\s+(.+?)\s*$') {
            return $Matches[1]
        }
    }
    return $null
}

<#
    The major version of the java.exe a server would be started with, or 0 when there is none. The servers are built for Java 25: an older one
    stops at once with an UnsupportedClassVersionError, on a console that closes with the JVM, so the only thing a user sees is a port that
    never opens. 'java -version' writes to stderr, which Windows PowerShell 5.1 turns into an error under 'Stop' once redirected.
#>
function Get-JavaMajor {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $said = & java.exe -version 2>&1 | ForEach-Object { "$_" }
    } catch {
        return 0
    } finally {
        $ErrorActionPreference = $previous
    }
    if (($said -join ' ') -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

function Start-AionServer([string]$Name, [int]$Port) {
    if (Test-PortListening $Port) {
        Write-Host "$Name already running (port $Port)" -ForegroundColor Yellow
        return
    }

    $folder = Join-Path $ServerRoot $Name
    $script = Join-Path $folder 'start.bat'
    if (-not (Test-Path $script)) {
        throw "Not found: $script"
    }

    $javaMajor = Get-JavaMajor
    if ($javaMajor -lt 25) {
        throw "$Name needs Java 25 or later, and the java.exe found first on the PATH is " + $(if ($javaMajor -eq 0) { 'missing or unreadable' } else { "version $javaMajor" }) + ". Install Java 25 and put it first on the PATH."
    }

    Write-Host "Starting $Name..." -ForegroundColor Cyan
    # Java is started directly rather than through start.bat, so that stopping the server also closes its window.
    # A batch script cannot be interrupted quietly: cmd asks whether to terminate the job, and start.bat then waits
    # on a PAUSE of its own, so both leave a console sitting there for someone to dismiss by hand. Without the batch
    # there is no prompt at all — the window belongs to the JVM and goes when it goes. The cost is start.bat's
    # restart-on-exit-code-2 loop, which a development server does not need.
    $arguments = Get-JavaArguments $script
    if ($arguments) {
        $process = Start-Process -FilePath 'java.exe' -ArgumentList $arguments -WorkingDirectory $folder -PassThru
    } else {
        Write-Host "  no java command found in start.bat, falling back to it (its window will outlive the server)" -ForegroundColor Yellow
        $process = Start-Process -FilePath $script -WorkingDirectory $folder -PassThru
    }

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening $Port) {
            Write-Host "$Name is up (port $Port)" -ForegroundColor Green
            return
        }
        # A JVM that has stopped will never listen, and its window went with it: say so now, with what its log still holds, rather than after the timeout
        if ($process -and $process.HasExited) {
            $said = @()
            foreach ($logFile in 'server_errors.log', 'server_console.log') {
                $path = Join-Path $folder "log\$logFile"
                if (Test-Path $path) { $said += "--- last lines of $logFile"; $said += Get-Content $path -Tail 15 }
            }
            throw "$Name stopped with exit code $($process.ExitCode) before it listened on port $Port. $($said -join [Environment]::NewLine)"
        }
        Start-Sleep -Milliseconds 500
    }
    throw "$Name did not start listening on port $Port within $TimeoutSeconds seconds. Check its window for errors."
}

if (-not $SkipChat) {
    Start-AionServer 'chat-server' 9021
}
Start-AionServer 'login-server' 9014
Start-AionServer 'game-server' 7777

<#
    The game port opens minutes before anyone can play: the game server registers with the login server only once its startup is over, bots
    included (149 to 278 s on 18 October). A client that tries in between sees the server as down, and has to be restarted to see it come up.
    So the stack is ready when the game server's log says it reached the login server, not when its port opens.
#>
function Wait-LoginRegistration {
    $log = Join-Path $ServerRoot 'game-server\log\server_console.log'
    $began = Get-Date
    $deadline = $began.AddSeconds($RegisterTimeoutSeconds)
    $nextReport = $began.AddSeconds(30)
    Write-Host 'Waiting for the game server to register with the login server...' -ForegroundColor Cyan
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'Connected to login server' -SimpleMatch -Quiet)) {
            Write-Host ("game-server is registered with the login server after {0:N0} s" -f ((Get-Date) - $began).TotalSeconds) -ForegroundColor Green
            return
        }
        if ((Get-Date) -ge $nextReport) {
            Write-Host ("  still starting ({0:N0} s)" -f ((Get-Date) - $began).TotalSeconds)
            $nextReport = $nextReport.AddSeconds(30)
        }
        Start-Sleep -Seconds 2
    }
    throw "The game server did not register with the login server within $RegisterTimeoutSeconds seconds. Check its window and log."
}

Wait-LoginRegistration

Write-Host 'Server stack is up: clients can connect.' -ForegroundColor Green

# Read what the game server wrote once the bots have begun to act, because that is when the faults showed: a tick that threw every second, and a server
# whose console had been clicked into and stood still. It reports and does not stop anything.
if (-not $SkipCheck) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'check-server-log.ps1') -ServerRoot $ServerRoot -WaitSeconds $CheckSeconds
}
