<#
.SYNOPSIS
    Stops MySQL cleanly with mysqladmin shutdown. Never kills it.

.DESCRIPTION
    Asks the running MySQL to shut down under the game server's own database account, read from its configuration, and waits for the port to
    close. MySQL flushes and closes its files on that path; a killed one has to replay its log at the next start.

    The account needs the SHUTDOWN privilege (global). A refusal is reported with the statement to give it, and nothing is killed. Run it once
    as an administrator of the database:

        GRANT SHUTDOWN ON *.* TO '<the account>'@'localhost';

    Stop the game server first (stop-server.ps1): it saves its data through this database while it exits.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER MysqlBin
    The folder of mysqladmin.exe, when it is not under C:\Program Files\MySQL or on the PATH.

.PARAMETER TimeoutSeconds
    How long to wait for the port to close.

.EXAMPLE
    .\tools\stop-mysql.ps1
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [string]$MysqlBin,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mysql-common.ps1')
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }

$game = Read-DatabaseSettings $ServerRoot ($script:AionServers | Where-Object Name -eq 'game')
if (-not $game) { throw "No usable database.url in the game server's configuration under $ServerRoot." }

if (-not (Test-PortListening $game.Port)) {
    Write-Host "MySQL is not running (port $($game.Port))." -ForegroundColor Yellow
    return
}

$gameServer = Get-RunningGameServer $ServerRoot
if ($gameServer) { throw "This install's game server is running (PID $gameServer): it saves through this database while it exits. Run stop-server.ps1 first." }

$mysqladmin = Find-MySqlTool 'mysqladmin.exe' $MysqlBin $ServerRoot
Write-Host "Shutting MySQL down (account $($game.User))..." -ForegroundColor Cyan

# stderr of a native command is an error record under 'Stop' in Windows PowerShell 5.1: read it as text instead
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$env:MYSQL_PWD = $game.Password
try {
    $said = & $mysqladmin --protocol=TCP -h $game.Host -P $game.Port -u $game.User shutdown 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    $ErrorActionPreference = $previous
}
if ($code -ne 0) {
    throw "mysqladmin refused: $($said -join ' ') If it is the SHUTDOWN privilege, give it once with: GRANT SHUTDOWN ON *.* TO '$($game.User)'@'localhost'; (MySQL was not touched.)"
}

# only the processes of the MySQL we stopped: another install's, or the installer's, may be running beside it
$binFolder = Split-Path $mysqladmin -Parent
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while ((Get-Date) -lt $deadline) {
    $still = @(Get-Process mysqld -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$binFolder*" })
    if (-not (Test-PortListening $game.Port) -and $still.Count -eq 0) {
        Write-Host 'MySQL stopped.' -ForegroundColor Green
        return
    }
    Start-Sleep -Milliseconds 500
}
throw "MySQL accepted the shutdown but is still there after $TimeoutSeconds seconds. It is finishing its writes: do not kill it, look again in a minute."
