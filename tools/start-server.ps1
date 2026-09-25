<#
.SYNOPSIS
    Starts the local Aion server stack, each server in its own window.

.DESCRIPTION
    Starts the login server first, waits for it to accept game server connections, then the game server.
    The chat server is optional and only needed for in-game chat channels.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER WithChat
    Also start the chat server.

.PARAMETER LoginDelaySeconds
    Seconds to wait after starting the login server before starting the game server.

.EXAMPLE
    .\tools\start-server.ps1
    .\tools\start-server.ps1 -WithChat
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$WithChat,
    [int]$LoginDelaySeconds = 8
)

$ErrorActionPreference = 'Stop'

if (-not $ServerRoot) {
    throw "No server path. Set the AION_SERVER_HOME environment variable or pass -ServerRoot."
}

function Start-AionServer([string]$name) {
    $folder = Join-Path $ServerRoot $name
    $script = Join-Path $folder 'start.bat'
    if (-not (Test-Path $script)) {
        throw "Not found: $script"
    }
    Start-Process -FilePath $script -WorkingDirectory $folder
    Write-Host "Started $name" -ForegroundColor Green
}

Start-AionServer 'login-server'

Write-Host "Waiting ${LoginDelaySeconds}s for the login server..." -ForegroundColor Cyan
Start-Sleep -Seconds $LoginDelaySeconds

if ($WithChat) {
    Start-AionServer 'chat-server'
}

Start-AionServer 'game-server'
