<#
.SYNOPSIS
    Starts the local Aion server stack in dependency order, each server in its own window.

.DESCRIPTION
    Order matters: the game server connects to both the chat server and the login server on startup,
    so they must already be listening. Each step waits for the next server's port to actually accept
    connections instead of sleeping for a fixed duration.

      chat-server   port 9021 (game server connections)
      login-server  port 9014 (game server connections)
      game-server   port 7777 (game clients)

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER SkipChat
    Do not start the chat server (in-game chat channels will not work).

.PARAMETER TimeoutSeconds
    How long to wait for each server to start listening before giving up.

.EXAMPLE
    .\tools\start-server.ps1
    .\tools\start-server.ps1 -SkipChat
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$SkipChat,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

if (-not $ServerRoot) {
    throw "No server path. Set the AION_SERVER_HOME environment variable or pass -ServerRoot."
}

function Test-PortListening([int]$Port) {
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
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

    Write-Host "Starting $Name..." -ForegroundColor Cyan
    Start-Process -FilePath $script -WorkingDirectory $folder

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-PortListening $Port) {
            Write-Host "$Name is up (port $Port)" -ForegroundColor Green
            return
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

Write-Host 'Server stack is up.' -ForegroundColor Green
