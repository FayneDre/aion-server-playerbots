# Shared by start-mysql.ps1, stop-mysql.ps1 and backup-db.ps1 (dot-sourced): where the MySQL tools are, which option file the program
# starts with, whether a port answers, and the account each server uses (read from its own configuration, never written here).

# What tells the three servers apart: their folder and the file that overrides their database settings.
$script:AionServers = @(
    [pscustomobject]@{ Name = 'game';  Folder = 'game-server';  Override = 'mygs.properties' },
    [pscustomobject]@{ Name = 'login'; Folder = 'login-server'; Override = 'myls.properties' },
    [pscustomobject]@{ Name = 'chat';  Folder = 'chat-server';  Override = 'mycs.properties' }
)

# The portable MySQL an install carries in <install>\mysql (setup-mysql.ps1 makes the database in it); $null when the install has none, and the
# callers then fall back to the installer's MySQL, as they always did.
function Get-PortableMysql([string]$ServerRoot) {
    if (-not $ServerRoot) { return $null }
    $mysqlHome = Join-Path $ServerRoot 'mysql'
    if (-not (Test-Path (Join-Path $mysqlHome 'bin\mysqld.exe'))) { return $null }
    [pscustomobject]@{ Home = $mysqlHome; Bin = Join-Path $mysqlHome 'bin'; Ini = Join-Path $mysqlHome 'my.ini'; Data = Join-Path $mysqlHome 'data' }
}

function Find-MySqlTool([string]$Name, [string]$MysqlBin, [string]$ServerRoot) {
    if ($MysqlBin -and (Test-Path (Join-Path $MysqlBin $Name))) { return Join-Path $MysqlBin $Name }
    $portable = Get-PortableMysql $ServerRoot
    if ($portable -and (Test-Path (Join-Path $portable.Bin $Name))) { return Join-Path $portable.Bin $Name }
    $onPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $found = foreach ($root in 'C:\Program Files\MySQL', 'C:\Program Files (x86)\MySQL') {
        if (Test-Path $root) { Get-ChildItem $root -Recurse -Filter $Name -ErrorAction SilentlyContinue }
    }
    $best = $found | Sort-Object FullName | Select-Object -Last 1
    if (-not $best) { throw "$Name not found: pass -MysqlBin (the folder of mysqld.exe, mysqladmin.exe and mysqldump.exe)." }
    return $best.FullName
}

# The option file of the install's portable MySQL, else the one the installer wrote (the newest version when there are several).
function Find-OptionsFile([string]$ServerRoot) {
    $portable = Get-PortableMysql $ServerRoot
    if ($portable -and (Test-Path $portable.Ini)) { return $portable.Ini }
    $found =Get-ChildItem 'C:\ProgramData\MySQL' -Recurse -Filter my.ini -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -Last 1
    if (-not $found) { throw 'No my.ini under C:\ProgramData\MySQL: pass -DefaultsFile.' }
    return $found.FullName
}

# The process id(s) of THIS install's game server, or $null. A server's command line names its class but not its install ("-cp libs/*"), so a server of
# another install would read as this one; what only this install's server does is hold its own jar open (the JVM keeps it open as long as it runs).
function Get-RunningGameServer([string]$ServerRoot) {
    $libs = Join-Path (Join-Path $ServerRoot 'game-server') 'libs'
    $held = Get-ChildItem $libs -Filter 'game-server-*.jar' -ErrorAction SilentlyContinue | Where-Object {
        try { [IO.File]::Open($_.FullName, 'Open', 'Read', 'None').Close(); $false } catch { $true }
    }
    if (-not $held) { return $null }
    $running = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*com.aionemu.gameserver.GameServer*' }
    if ($running) { return ($running.ProcessId -join ', ') } else { return 'unknown' }
}

# Asks the system's table first, then connects (the table alone has answered nothing on this machine while every port was open).
function Test-PortListening([int]$Port) {
    if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { return $true }
    $client = New-Object Net.Sockets.TcpClient
    try { return $client.ConnectAsync('127.0.0.1', $Port).Wait(1000) -and $client.Connected } catch { return $false } finally { $client.Close() }
}

# The database settings of one server: its database.properties, then the override file, which wins. Returns host, port, schema, user, password.
function Read-DatabaseSettings([string]$ServerRoot, $Server) {
    $props = @{}
    $configDir = Join-Path (Join-Path $ServerRoot $Server.Folder) 'config'
    foreach ($file in @((Join-Path $configDir 'network\database.properties'), (Join-Path $configDir $Server.Override))) {
        if (-not (Test-Path $file)) { continue }
        foreach ($line in Get-Content $file -Encoding UTF8) {
            if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
        }
    }
    if ($props['database.url'] -notmatch '^jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)') { return $null }
    [pscustomobject]@{
        Host = $Matches[1]; Port = $(if ($Matches[2]) { [int]$Matches[2] } else { 3306 }); Schema = $Matches[3]
        User = $props['database.user']; Password = $props['database.password']
    }
}
