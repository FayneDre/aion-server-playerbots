<#
.SYNOPSIS
    Gives the game server's database account the right to shut MySQL down, once, so that stop-mysql.ps1 can stop it cleanly.

.DESCRIPTION
    Run it yourself in a terminal: it asks for the password of a database administrator (root by default) and uses it for that one statement,

        GRANT SHUTDOWN ON *.* TO '<the game account>'@'<its host>';

    The password is read as a secure string, handed to mysql through the environment of that one process and never written anywhere, nor
    put on a command line. The account and its host are read from the game server's configuration and asked of MySQL itself.
    Nothing happens when the account already has the privilege.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER AdminUser
    The administrator account that may grant privileges.

.PARAMETER MysqlBin
    The folder of mysql.exe, when it is not under C:\Program Files\MySQL or on the PATH.

.EXAMPLE
    .\tools\grant-shutdown.ps1
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [string]$AdminUser = 'root',
    [string]$MysqlBin
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mysql-common.ps1')
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }

$game = Read-DatabaseSettings $ServerRoot ($script:AionServers | Where-Object Name -eq 'game')
if (-not $game) { throw "No usable database.url in the game server's configuration under $ServerRoot." }
if (-not (Test-PortListening $game.Port)) { throw "MySQL does not answer on port $($game.Port): run start-mysql.ps1 first." }
$mysql = Find-MySqlTool 'mysql.exe' $MysqlBin

# Runs one statement as the given account; the password goes through the environment of this call only. Native stderr is read as text (5.1).
function Invoke-MySql([string]$User, [string]$Password, [string]$Statement) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $env:MYSQL_PWD = $Password
    try {
        $out = & $mysql --protocol=TCP -h $game.Host -P $game.Port -u $User --batch --skip-column-names -e $Statement 2>&1 | ForEach-Object { "$_" }
        [pscustomobject]@{ Code = $LASTEXITCODE; Output = ($out -join "`n") }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        $ErrorActionPreference = $previous
    }
}

# who the game account really is to MySQL (user@host), and whether it can already shut it down
$who = Invoke-MySql $game.User $game.Password 'SELECT CURRENT_USER()'
if ($who.Code -ne 0) { throw "The game account cannot connect: $($who.Output)" }
$account = $who.Output.Trim()
$has = Invoke-MySql $game.User $game.Password 'SHOW GRANTS'
if ($has.Output -match 'SHUTDOWN|ALL PRIVILEGES ON \*\.\*') {
    Write-Host "$account can already shut MySQL down." -ForegroundColor Green
    return
}

$secure = Read-Host "Password of the database account '$AdminUser' (kept in memory for this one statement)" -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }

$parts = $account -split '@', 2
$grant = Invoke-MySql $AdminUser $password "GRANT SHUTDOWN ON *.* TO '$($parts[0])'@'$($parts[1])'"
$password = $null
if ($grant.Code -ne 0) { throw "MySQL refused: $($grant.Output)" }

$check = Invoke-MySql $game.User $game.Password 'SHOW GRANTS'
if ($check.Output -notmatch 'SHUTDOWN') { throw "The statement went through but $account does not show the privilege: $($check.Output)" }
Write-Host "$account can now shut MySQL down: stop-mysql.ps1 and stop-server.ps1 -WithMysql work." -ForegroundColor Green
