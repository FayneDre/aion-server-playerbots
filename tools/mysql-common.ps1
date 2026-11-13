# Shared by start-mysql.ps1, stop-mysql.ps1 and backup-db.ps1 (dot-sourced): where the MySQL tools are, which option file the program
# starts with, whether a port answers, and the account each server uses (read from its own configuration, never written here).

# What tells the three servers apart: their folder and the file that overrides their database settings.
$script:AionServers = @(
    [pscustomobject]@{ Name = 'game';  Folder = 'game-server';  Override = 'mygs.properties' },
    [pscustomobject]@{ Name = 'login'; Folder = 'login-server'; Override = 'myls.properties' },
    [pscustomobject]@{ Name = 'chat';  Folder = 'chat-server';  Override = 'mycs.properties' }
)

function Find-MySqlTool([string]$Name, [string]$MysqlBin) {
    if ($MysqlBin -and (Test-Path (Join-Path $MysqlBin $Name))) { return Join-Path $MysqlBin $Name }
    $onPath = Get-Command $Name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $found = foreach ($root in 'C:\Program Files\MySQL', 'C:\Program Files (x86)\MySQL') {
        if (Test-Path $root) { Get-ChildItem $root -Recurse -Filter $Name -ErrorAction SilentlyContinue }
    }
    $best = $found | Sort-Object FullName | Select-Object -Last 1
    if (-not $best) { throw "$Name not found: pass -MysqlBin (the folder of mysqld.exe, mysqladmin.exe and mysqldump.exe)." }
    return $best.FullName
}

# The option file the installer wrote, the newest version when there are several.
function Find-OptionsFile {
    $found = Get-ChildItem 'C:\ProgramData\MySQL' -Recurse -Filter my.ini -ErrorAction SilentlyContinue | Sort-Object FullName | Select-Object -Last 1
    if (-not $found) { throw 'No my.ini under C:\ProgramData\MySQL: pass -DefaultsFile.' }
    return $found.FullName
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
