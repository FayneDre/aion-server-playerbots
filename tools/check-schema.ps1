<#
.SYNOPSIS
    Checks that a server's schema makes the same tables as its live database after update.sql: a database made from nothing (apply-sql.ps1
    -Create) into a scratch MySQL, then both compared table by table, column by column. Changes nothing in the live database.

.DESCRIPTION
    An install from nothing runs the schema (aion_gs.sql, aion_ls.sql, aion_cs.sql) and records update.sql as applied, trusting that the schema
    holds what update.sql adds. Upstream keeps both, and nothing checks they agree: this does, after every upstream merge, and before a release
    that installs from nothing (docs/architecture/plans/fresh-install-readiness-plan.md, milestone 1).

    The scratch MySQL is one whose user may create databases (the live server's user may not): the portable MySQL the launcher ships, started on
    its own port. The database made there is named <schema>_check and dropped afterwards. Only the structure is compared: the columns and keys
    of each table and its options, the AUTO_INCREMENT counter left out. The ledger table of apply-sql.ps1 is left out, since a live database
    may not have it.

    Exit code 0 when they agree, 1 with the differences listed.

.PARAMETER ServerRoot
    The live install, whose configuration gives the live database. Defaults to AION_SERVER_HOME.

.PARAMETER Server
    game (the default), login or chat.

.PARAMETER ScratchPort
    The scratch MySQL's port, on 127.0.0.1. Its user is root without a password unless -ScratchUser and -ScratchPassword say otherwise.

.PARAMETER MysqlBin
    The folder of mysql.exe and mysqldump.exe; the scratch MySQL's own is the safest.
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [ValidateSet('game', 'login', 'chat')]
    [string]$Server = 'game',
    [int]$ScratchPort = 3307,
    [string]$ScratchUser = 'root',
    [string]$ScratchPassword = '',
    [Parameter(Mandatory = $true)]
    [string]$MysqlBin
)

$ErrorActionPreference = 'Stop'
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }
$folder = @{ game = 'game-server'; login = 'login-server'; chat = 'chat-server' }[$Server]
$override = @{ game = 'mygs.properties'; login = 'myls.properties'; chat = 'mycs.properties' }[$Server]
$ledger = 'schema_applied_sql'

# the live database, read the way the server reads it
$props = @{}
foreach ($file in @((Join-Path $ServerRoot "$folder\config\network\database.properties"), (Join-Path $ServerRoot "$folder\config\$override"))) {
    if (-not (Test-Path $file)) { continue }
    foreach ($line in Get-Content $file -Encoding UTF8) { if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] } }
}
if ($props['database.url'] -notmatch '^jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)') { throw "No usable database.url for the $Server server in $ServerRoot." }
$liveHost, $livePort, $schema = $Matches[1], $(if ($Matches[2]) { $Matches[2] } else { '3306' }), $Matches[3]
$check = "${schema}_check"

$work = Join-Path $env:TEMP "aion-check-schema-$PID"
New-Item -ItemType Directory -Force $work | Out-Null
function Escape-Option([string]$value) { '"' + $value.Replace('\', '\\').Replace('"', '\"') + '"' }
function Write-Cnf([string]$path, [string]$hostName, [string]$port, [string]$user, [string]$password) {
    Set-Content $path @('[client]', "host=$hostName", "port=$port", "user=$(Escape-Option $user)", "password=$(Escape-Option $password)",
        'default-character-set=utf8mb4') -Encoding ascii
}
$liveCnf = Join-Path $work 'live.cnf'
$scratchCnf = Join-Path $work 'scratch.cnf'
Write-Cnf $liveCnf $liveHost $livePort $props['database.user'] $props['database.password']
Write-Cnf $scratchCnf '127.0.0.1' $ScratchPort $ScratchUser $ScratchPassword
$mysql = Join-Path $MysqlBin 'mysql.exe'
$dump = Join-Path $MysqlBin 'mysqldump.exe'

# each table's lines (columns, keys) as a set, and its options, from a structure-only dump
function Read-Tables([string]$cnf, [string]$database) {
    $out = cmd /c "`"$dump`" --defaults-extra-file=`"$cnf`" --no-data --skip-lock-tables --no-tablespaces --skip-comments `"$database`" 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "Could not dump $database`: $($out | Select-Object -Last 3)" }
    $tables = @{}
    $text = ($out | Where-Object { $_ -notmatch 'Using a password' }) -join "`n"
    foreach ($m in [regex]::Matches($text, '(?s)CREATE TABLE `([^`]+)` \((.*?)\n\)([^;]*);')) {
        $lines = New-Object Collections.Generic.HashSet[string]
        foreach ($line in $m.Groups[2].Value -split "`n") { $l = $line.Trim().TrimEnd(','); if ($l) { [void]$lines.Add($l) } }
        $tables[$m.Groups[1].Value] = @{ Lines = $lines; Options = ([regex]::Replace($m.Groups[3].Value, ' AUTO_INCREMENT=\d+', '')).Trim() }
    }
    return $tables
}

# defined before the try, so the finally never meets an unset path and hides the error that brought it there
$drop = Join-Path $work 'drop.sql'
try {
    # the scratch database made from nothing, as an install makes it, through a configuration of its own
    $fakeRoot = Join-Path $work 'install'
    New-Item -ItemType Directory -Force (Join-Path $fakeRoot "$folder\config") | Out-Null
    Set-Content (Join-Path $fakeRoot "$folder\config\$override") @("database.url = jdbc:mysql://127.0.0.1:$ScratchPort/$check",
        "database.user = $ScratchUser", "database.password = $ScratchPassword") -Encoding ascii
    Set-Content $drop "DROP DATABASE IF EXISTS ``$check``;" -Encoding ascii
    cmd /c "`"$mysql`" --defaults-extra-file=`"$scratchCnf`" < `"$drop`" 2>&1" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "The scratch MySQL on port $ScratchPort does not answer." }
    & (Join-Path $PSScriptRoot 'apply-sql.ps1') -ServerRoot $fakeRoot -Server $Server -MysqlBin $MysqlBin -LedgerTable $ledger -Create -Apply -Scratch |
        Select-Object -Last 1 | Write-Output

    $live = Read-Tables $liveCnf $schema
    $fresh = Read-Tables $scratchCnf $check
    $differences = New-Object Collections.Generic.List[string]
    foreach ($name in ($live.Keys + $fresh.Keys | Sort-Object -Unique)) {
        if ($name -eq $ledger) { continue }
        if (-not $fresh.ContainsKey($name)) { $differences.Add("only live: $name"); continue }
        if (-not $live.ContainsKey($name)) { $differences.Add("only from the schema: $name"); continue }
        foreach ($line in $live[$name].Lines) { if (-not $fresh[$name].Lines.Contains($line)) { $differences.Add("$name, live: $line") } }
        foreach ($line in $fresh[$name].Lines) { if (-not $live[$name].Lines.Contains($line)) { $differences.Add("$name, schema: $line") } }
        if ($live[$name].Options -ne $fresh[$name].Options) { $differences.Add("$name, options: live [$($live[$name].Options)], schema [$($fresh[$name].Options)]") }
    }
    $compared = @($live.Keys | Where-Object { $_ -ne $ledger }).Count
    if ($differences.Count -eq 0) {
        Write-Output "$schema`: the schema and update.sql make the live database's $compared table(s), column for column."
        $code = 0
    } else {
        Write-Output "$schema`: $($differences.Count) difference(s) between the live database and a database made from the schema:"
        $differences | ForEach-Object { Write-Output "  $_" }
        $code = 1
    }
} finally {
    if (Test-Path -LiteralPath $drop) { cmd /c "`"$mysql`" --defaults-extra-file=`"$scratchCnf`" < `"$drop`" 2>&1" | Out-Null }
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
# a real exit, so a caller in another process (powershell -File) sees it too
exit $code
