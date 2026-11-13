<#
.SYNOPSIS
    Dumps the databases of the installed servers into <server>/backups/auto-<date>, and keeps the newest few.

.DESCRIPTION
    One .sql file per database (aion_gs, aion_ls, aion_cs), each written with the account its own server uses, read from that server's
    configuration. A dump counts only if it ends with the "Dump completed" line; the others are reported and the folder is kept for a look.
    Older automatic folders beyond -Keep are removed; folders of other names (apply-sql.ps1's, a hand-made one) are never touched.

    stop-server.ps1 runs this once the game server has saved and exited, so every stop, and every deploy that restarts, leaves a dump.
    The binary log is off (tools/mysql/my-light.ini), so this is the safety net: a dump is readable, small (about 22 MB) and restores into any MySQL.

.PARAMETER ServerRoot
    Root of the server installation. Defaults to the AION_SERVER_HOME environment variable.

.PARAMETER Keep
    How many automatic backups to keep.

.PARAMETER MysqlBin
    The folder of mysqldump.exe, when it is not under C:\Program Files\MySQL or on the PATH.

.EXAMPLE
    .\tools\backup-db.ps1
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [int]$Keep = 10,
    [string]$MysqlBin
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mysql-common.ps1')
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }

$dump = Find-MySqlTool 'mysqldump.exe' $MysqlBin
$backups = Join-Path $ServerRoot 'backups'
$folder = Join-Path $backups ('auto-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $folder | Out-Null

$failed = @()
$done = 0
foreach ($server in $script:AionServers) {
    $db = Read-DatabaseSettings $ServerRoot $server
    if (-not $db) { continue }   # a server this install does not have
    if (-not (Test-PortListening $db.Port)) { throw "MySQL does not answer on port $($db.Port): nothing to dump. Run start-mysql.ps1." }
    $file = Join-Path $folder "$($db.Schema).sql"
    $errors = Join-Path $env:TEMP ("aion-backup-$PID-$($server.Name).txt")

    # The options both MySQL and MariaDB know, and none that needs more than the game account has: --single-transaction asks for RELOAD, which it
    # lacks (the dump is taken with the servers idle in practice: stop-server.ps1 runs this once the game server has exited).
    # Native stderr is read as text, since 5.1 turns it into an error under 'Stop'.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $env:MYSQL_PWD = $db.Password
    try {
        & $dump --protocol=TCP -h $db.Host -P $db.Port -u $db.User --skip-lock-tables --no-tablespaces --routines `
            "--result-file=$file" $db.Schema 2> $errors
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        $ErrorActionPreference = $previous
    }

    if ((Test-Path $file) -and (Select-String -Path $file -Pattern '^-- Dump completed' -Quiet)) {
        Write-Host ("  {0}: {1:N1} MB" -f $db.Schema, ((Get-Item $file).Length / 1MB))
        $done++
    } else {
        $failed += "$($db.Schema): $((Select-String -Path $errors -Pattern 'mysqldump: ' -ErrorAction SilentlyContinue | Select-Object -Last 2 | ForEach-Object { $_.Line.Trim() }) -join ' ')"
    }
    Remove-Item $errors -ErrorAction SilentlyContinue
}

if ($failed.Count -gt 0) { throw "Backup incomplete, $folder kept for a look:`n  $($failed -join "`n  ")" }
if ($done -eq 0) { throw "No server with a database found under $ServerRoot." }
Write-Host "Backup: $folder" -ForegroundColor Green

# only this script's own folders, by name, are ever removed
$old = Get-ChildItem $backups -Directory | Where-Object { $_.Name -match '^auto-\d{8}-\d{6}$' } | Sort-Object Name -Descending | Select-Object -Skip $Keep
foreach ($directory in $old) { Remove-Item $directory.FullName -Recurse -Force }
if ($old) { Write-Host "  removed $(@($old).Count) older automatic backup(s)" -ForegroundColor DarkGray }
