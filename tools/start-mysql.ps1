<#
.SYNOPSIS
    Starts MySQL as a program and waits until its port answers: no Windows service, no administrator rights.

.DESCRIPTION
    The installer's service (MySQL267 on this machine) is disabled and needs administrator rights to start, so after every reboot MySQL had to be
    started by hand before start-server.ps1. This starts mysqld.exe with the installer's option file instead, hidden and detached, the same way
    the launcher's "start with my own my.ini" mode does. A MySQL already answering on the port (the service, or an earlier run of this script)
    is left alone.

    stop-mysql.ps1 stops it again, cleanly; start-server.ps1 calls this one first.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable. An install that carries its own MySQL (<install>\mysql,
    made by setup-mysql.ps1) starts that one with its own my.ini; any other starts the installer's.

.PARAMETER DefaultsFile
    The option file. Defaults to the install's own, else the installer's my.ini under C:\ProgramData\MySQL.

.PARAMETER MysqlBin
    The folder of mysqld.exe, when it is not the install's own, under C:\Program Files\MySQL or on the PATH.

.PARAMETER Port
    The port MySQL is expected to answer on. Defaults to the one the game server's database.url names, else 3306.

.PARAMETER TimeoutSeconds
    How long to wait for the port before giving up.

.EXAMPLE
    .\tools\start-mysql.ps1
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [string]$DefaultsFile,
    [string]$MysqlBin,
    [int]$Port = 0,
    [int]$TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mysql-common.ps1')

if ($Port -eq 0) {
    $game = if ($ServerRoot) { Read-DatabaseSettings $ServerRoot ($script:AionServers | Where-Object Name -eq 'game') } else { $null }
    $Port = if ($game) { $game.Port } else { 3306 }
}

if (Test-PortListening $Port) {
    Write-Host "MySQL already running (port $Port)" -ForegroundColor Yellow
    return
}

if (-not $DefaultsFile) { $DefaultsFile = Find-OptionsFile $ServerRoot }
if (-not (Test-Path $DefaultsFile)) { throw "No option file at $DefaultsFile." }
$mysqld = Find-MySqlTool 'mysqld.exe' $MysqlBin $ServerRoot

Write-Host "Starting MySQL ($DefaultsFile)..." -ForegroundColor Cyan
$process = Start-Process -FilePath $mysqld -ArgumentList "--defaults-file=`"$DefaultsFile`"" -WindowStyle Hidden -PassThru

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    if (Test-PortListening $Port) {
        Write-Host "MySQL is up (port $Port)" -ForegroundColor Green
        return
    }
    if ($process.HasExited) { break }
    Start-Sleep -Milliseconds 500
}

# Said with what the server's own log holds: a port that never opens says nothing about why.
$said = @()
$options = Get-Content $DefaultsFile
$dataDir = ($options | Where-Object { $_ -match '^\s*datadir\s*=\s*"?([^"#]+?)"?\s*$' } | ForEach-Object { $Matches[1] } | Select-Object -Last 1)
$errorLog = ($options | Where-Object { $_ -match '^\s*log-error\s*=\s*"?([^"#]+?)"?\s*$' } | ForEach-Object { $Matches[1] } | Select-Object -Last 1)
if ($dataDir -and $errorLog) {
    $path = if ([IO.Path]::IsPathRooted($errorLog)) { $errorLog } else { Join-Path $dataDir $errorLog }
    if (Test-Path $path) { $said = @('--- last lines of ' + $path) + (Get-Content $path -Tail 12) }
}
throw "MySQL did not answer on port $Port within $TimeoutSeconds seconds. $($said -join [Environment]::NewLine)"
