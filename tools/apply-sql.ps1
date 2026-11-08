<#
.SYNOPSIS
    Applies the statements of a server's sql/update.sql that its database has not had yet, once each, after a backup; or, with -Create, makes the
    database from nothing. Shows what it would do without -Apply.

.DESCRIPTION
    Upstream keeps the changes to the schema in update.sql as statements to run by hand, and a merge brought some that rename columns the old server
    reads. By hand it is a mysqldump, a client, and remembering which statements the database already has; here the database remembers: a table,
    schema_applied_sql, holds the hash of every statement run, so a statement is never run twice and a deploy with nothing new does nothing.

    - Comments that start a line are dropped, and so are the statements naming playerbot_ (SchemaUpdater creates the bot's tables at start). Two equal
      statements are one. A file with DELIMITER (procedures, triggers) is refused: this runs plain statements.
    - With something new: the game server must be stopped, aion_gs is dumped to <install>/backups, then each statement is run and recorded. A statement
      that fails stops the run with its error; the ones before it stay recorded, and the next run goes on from there. A DDL statement that fails because
      the column, key or table is already (or no longer) there is recorded as already done.
    - A database that has the statements already, run by hand, is told so once with -MarkApplied.
    - -Create makes a database that has no table yet: the database created if missing, the server's schema (sql/aion_gs.sql, aion_ls.sql or
      aion_cs.sql) run into it, then every statement of update.sql recorded as applied, since the schema holds them already. A database with a
      table is refused. If the schema fails half way, what it made is taken away again, so the next run starts from nothing. This is what an
      install from nothing runs, once a server, so the rules of the SQL stay in this script.

    Each of the three servers has its own database (-Server game, login or chat), read the way that server reads it: its
    config/network/database.properties, then its own config/mygs.properties, myls.properties or mycs.properties on top.

.PARAMETER ServerRoot
    The install. Defaults to AION_SERVER_HOME.

.PARAMETER Server
    game (the default), login or chat: whose database, configuration, update.sql and schema.

.PARAMETER SqlFile
    The statements. Defaults to <server>/sql/update.sql beside this script (the repository's, or the release bundle's). A server with no
    update.sql (chat) has none.

.PARAMETER SchemaFile
    For -Create: the schema. Defaults to <server>/sql/aion_gs.sql, aion_ls.sql or aion_cs.sql beside this script.

.PARAMETER Create
    Make the database from nothing (with -Apply; without it, say what would be done).

.PARAMETER MarkApplied
    With -Apply, record the new statements without running them: for a database that has them.

.PARAMETER Scratch
    For trying the script on a file of scratch statements: no backup, and the game server may be running. Use -LedgerTable with it.

.PARAMETER Apply
    Do it.
#>
param(
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [ValidateSet('game', 'login', 'chat')]
    [string]$Server = 'game',
    [string]$SqlFile,
    [string]$SchemaFile,
    [string]$LedgerTable = 'schema_applied_sql',
    [string]$MysqlBin,
    [switch]$MarkApplied,
    [switch]$Create,
    [switch]$Scratch,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }
if ($Create -and $MarkApplied) { throw '-Create records the statements itself: -MarkApplied is for a database that exists.' }

# what tells the three servers apart: their folder, their override file, their schema and the class a running one shows on its command line
$servers = @{
    game  = @{ Folder = 'game-server'; Override = 'mygs.properties'; Schema = 'aion_gs.sql'; MainClass = 'com.aionemu.gameserver.GameServer' }
    login = @{ Folder = 'login-server'; Override = 'myls.properties'; Schema = 'aion_ls.sql'; MainClass = 'com.aionemu.loginserver.LoginServer' }
    chat  = @{ Folder = 'chat-server'; Override = 'mycs.properties'; Schema = 'aion_cs.sql'; MainClass = 'com.aionemu.chatserver.ChatServer' }
}
$target = $servers[$Server]
$sqlDir = Join-Path (Join-Path (Split-Path -Parent $PSScriptRoot) $target.Folder) 'sql'
if (-not $SqlFile) { $SqlFile = Join-Path $sqlDir 'update.sql' }
if (-not $SchemaFile) { $SchemaFile = Join-Path $sqlDir $target.Schema }
if ($Create -and -not (Test-Path $SchemaFile)) { throw "No schema at $SchemaFile." }

# ---- the statements -------------------------------------------------------------------------------------------------------------------------
$text = if (Test-Path $SqlFile) { [IO.File]::ReadAllText($SqlFile) } else { '' }
# comments are only taken out where they start a line, so that a string holding /* or -- is left alone
$text = [regex]::Replace($text, '(?ms)^[ \t]*/\*.*?\*/', '')
$text = [regex]::Replace($text, '(?m)^[ \t]*(--|#).*$', '')
if ($text -match '(?im)^\s*DELIMITER\b') { throw "$SqlFile uses DELIMITER (procedures, triggers): this script runs plain statements; run that part by hand." }
$statements = New-Object Collections.Generic.List[object]
$hashes = @{}
foreach ($raw in [regex]::Split($text, ';[ \t]*(?:\r?\n|$)')) {
    $statement = $raw.Trim()
    if ($statement.Length -eq 0) { continue }
    if ($statement -match 'playerbot_') { continue } # the bot's tables are SchemaUpdater's, created at start
    $normal = [regex]::Replace($statement, '\s+', ' ')
    $hash = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($normal))).Replace('-', '').ToLower()
    if ($hashes[$hash]) { continue } # the same statement twice in the file is one: a statement is known by its text
    $hashes[$hash] = $true
    $statements.Add([pscustomobject]@{ Text = $statement; Hash = $hash })
}

# ---- the connection -------------------------------------------------------------------------------------------------------------------------
$props = @{}
$configDir = Join-Path (Join-Path $ServerRoot $target.Folder) 'config'
foreach ($file in @((Join-Path $configDir 'network\database.properties'), (Join-Path $configDir $target.Override))) {
    if (-not (Test-Path $file)) { continue }
    foreach ($line in Get-Content $file -Encoding UTF8) {
        if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] }
    }
}
if ($props['database.url'] -notmatch '^jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)') { throw "No usable database.url in $configDir." }
$dbHost, $dbPort, $schema = $Matches[1], $(if ($Matches[2]) { $Matches[2] } else { '3306' }), $Matches[3]

function Find-MySqlTool([string]$name) {
    if ($MysqlBin -and (Test-Path (Join-Path $MysqlBin $name))) { return Join-Path $MysqlBin $name }
    $onPath = Get-Command $name -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $found = foreach ($root in 'C:\Program Files\MySQL', 'C:\Program Files (x86)\MySQL') {
        if (Test-Path $root) { Get-ChildItem $root -Recurse -Filter $name -ErrorAction SilentlyContinue }
    }
    $best = $found | Sort-Object FullName | Select-Object -Last 1
    if (-not $best) { throw "$name not found: pass -MysqlBin." }
    return $best.FullName
}

function Escape-Option([string]$value) { '"' + $value.Replace('\', '\\').Replace('"', '\"') + '"' }

$cnf = Join-Path $env:TEMP ("aion-sql-$PID.cnf")
$work = Join-Path $env:TEMP ("aion-sql-$PID.sql")
try {
    Set-Content $cnf @('[client]', "host=$dbHost", "port=$dbPort", "user=$(Escape-Option $props['database.user'])",
        "password=$(Escape-Option $props['database.password'])", 'default-character-set=utf8mb4') -Encoding ascii
    $mysql = Find-MySqlTool 'mysql.exe'

    # a file of statements through the client, into the database or into none; the code and what it said
    function Invoke-SqlFile([string]$path, [string]$database = $schema) {
        $into = if ($database) { "`"$database`"" } else { '' }
        $out = cmd /c "`"$mysql`" --defaults-extra-file=`"$cnf`" --batch --skip-column-names $into < `"$path`" 2>&1"
        [pscustomobject]@{ Code = $LASTEXITCODE; Output = (($out | Where-Object { $_ -notmatch 'Using a password on the command line' }) -join "`n") }
    }
    function Invoke-Sql([string]$sql, [string]$database = $schema) {
        [IO.File]::WriteAllText($work, $sql, (New-Object Text.UTF8Encoding($false)))
        Invoke-SqlFile $work $database
    }
    function Quote([string]$s) { "'" + $s.Replace('\', '\\').Replace("'", "''") + "'" }
    function Test-ServerRunning {
        $running = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -like "*$($target.MainClass)*" }
        if ($running) { return $running[0].ProcessId }
    }
    $createLedger = "CREATE TABLE IF NOT EXISTS ``$LedgerTable`` (hash char(64) NOT NULL PRIMARY KEY, statement text NOT NULL, how varchar(10) NOT NULL, " +
        'applied_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;'
    function Record($s, [string]$how) {
        $r = Invoke-Sql "INSERT INTO ``$LedgerTable`` (hash, statement, how) VALUES ('$($s.Hash)', $(Quote $s.Text), '$how');"
        if ($r.Code -ne 0) { throw "Could not record a statement: $($r.Output)" }
    }

    # ---- a database from nothing -----------------------------------------------------------------------------------------------------------
    if ($Create) {
        $look = Invoke-Sql "SELECT (SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = $(Quote $schema)), (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = $(Quote $schema));" ''
        if ($look.Code -ne 0) { throw "Could not reach the database server: $($look.Output)" }
        $exists, $tables = ($look.Output.Trim() -split '\s+') | ForEach-Object { [int]$_ }
        if ($tables -gt 0) { throw "$schema has $tables table(s) already: -Create only makes a database from nothing. To bring one up to date, run without -Create." }
        Write-Output "Create: $schema $(if ($exists) { 'exists, empty' } else { 'is missing' }); $(Split-Path -Leaf $SchemaFile) into it, then $($statements.Count) statement(s) of $(Split-Path -Leaf $SqlFile) recorded as applied (the schema holds them)."
        if (-not $Apply) { Write-Output 'Nothing changed: -Apply does it.'; $global:LASTEXITCODE = 0; return }
        $running = Test-ServerRunning
        if ($running -and -not $Scratch) { throw "The $Server server is running (PID $running): stop it before its database is made." }

        if (-not $exists) {
            $made = Invoke-Sql "CREATE DATABASE ``$schema`` DEFAULT CHARACTER SET utf8mb4;" ''
            if ($made.Code -ne 0) { throw "Could not create $schema`: $($made.Output)" }
        }
        $loaded = Invoke-SqlFile $SchemaFile
        if ($loaded.Code -ne 0) {
            # taken back to nothing, so the next run is the same as this one: the database dropped if this run made it, its tables otherwise
            if (-not $exists) {
                Invoke-Sql "DROP DATABASE ``$schema``;" '' | Out-Null
            } else {
                $left = Invoke-Sql "SELECT table_name FROM information_schema.tables WHERE table_schema = $(Quote $schema);" ''
                $drops = @($left.Output -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { "DROP TABLE IF EXISTS ``$($_.Trim())``;" })
                if ($drops) { Invoke-Sql ("SET FOREIGN_KEY_CHECKS=0;`n" + ($drops -join "`n")) | Out-Null }
            }
            throw "The schema $(Split-Path -Leaf $SchemaFile) failed, and what it made was taken away again:`n$($loaded.Output)"
        }
        $ledger = Invoke-Sql $createLedger
        if ($ledger.Code -ne 0) { throw "Could not create the table of applied statements: $($ledger.Output)" }
        foreach ($s in $statements) { Record $s 'created' }
        $count = Invoke-Sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = $(Quote $schema);" ''
        Write-Output "Created $schema`: $($count.Output.Trim()) table(s), $($statements.Count) statement(s) recorded."
        $global:LASTEXITCODE = 0
        return
    }

    # ---- what the database has -------------------------------------------------------------------------------------------------------------
    $seen = @{}
    $answer = Invoke-Sql "SELECT hash FROM ``$LedgerTable``;"
    if ($answer.Code -eq 0) {
        foreach ($line in $answer.Output -split "`n") { if ($line.Trim()) { $seen[$line.Trim()] = $true } }
    } elseif ($answer.Output -notmatch 'ERROR 1146') {
        throw "Could not read the statements already applied: $($answer.Output)"
    }
    $pending = @($statements | Where-Object { -not $seen[$_.Hash] })
    # Read by the launcher before it updates a server from a release bundle: a database that has never been through this script is asked
    # about, rather than having the whole of update.sql run on it.
    Write-Output "Ledger: $(if ($answer.Code -eq 0) { 'present' } else { 'absent' })"
    Write-Output "SQL: $($statements.Count) statement(s) in $(Split-Path -Leaf $SqlFile), $($pending.Count) not applied yet to $schema."
    foreach ($s in $pending) { Write-Output ("  " + ([regex]::Replace($s.Text, '\s+', ' ')).Substring(0, [Math]::Min(100, ([regex]::Replace($s.Text, '\s+', ' ')).Length))) }
    if ($pending.Count -eq 0) { $global:LASTEXITCODE = 0; return }
    if (-not $Apply) { Write-Output 'Nothing changed: -Apply does it (-MarkApplied if the database has them already).'; $global:LASTEXITCODE = 0; return }

    $made = Invoke-Sql $createLedger
    if ($made.Code -ne 0) { throw "Could not create the table of applied statements: $($made.Output)" }

    if ($MarkApplied) {
        foreach ($s in $pending) { Record $s 'marked' }
        Write-Output "Marked $($pending.Count) statement(s) as applied; none was run."
        $global:LASTEXITCODE = 0
        return
    }

    # ---- the game server is stopped, the database dumped, the statements run -------------------------------------------------------------
    if (-not $Scratch) {
        $running = Test-ServerRunning
        if ($running) { throw "The $Server server is running (PID $running): these statements rename and rewrite what it reads. Stop it first." }
        $dump = Find-MySqlTool 'mysqldump.exe'
        $backups = Join-Path $ServerRoot 'backups'
        New-Item -ItemType Directory -Force $backups | Out-Null
        $backup = Join-Path $backups ("$schema-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.sql')
        $errors = Join-Path $env:TEMP ("aion-dump-$PID.txt")
        cmd /c "`"$dump`" --defaults-extra-file=`"$cnf`" --skip-lock-tables --no-tablespaces --routines $schema --result-file=`"$backup`" 2>`"$errors`""
        if (-not (Test-Path $backup) -or -not (Select-String -Path $backup -Pattern '^-- Dump completed' -Quiet)) {
            throw "The backup of $schema failed, nothing was changed: $(Get-Content $errors -ErrorAction SilentlyContinue | Select-Object -Last 3)"
        }
        Remove-Item $errors -ErrorAction SilentlyContinue
        Write-Output ("Backup: $backup ({0:N0} MB)" -f ((Get-Item $backup).Length / 1MB))
    }
    # duplicate column, duplicate key, table exists, can't drop what is not there, a table to drop that is gone: the statement's effect is in the database.
    # An unknown column (1054) is not in the list: it may as well be a statement that is wrong, and a wrong statement recorded as done is never run
    $benign = 'ERROR (1060|1061|1050|1091|1051)\b'
    foreach ($s in $pending) {
        $r = Invoke-Sql ($s.Text + ';')
        if ($r.Code -eq 0) {
            Record $s 'applied'
        } elseif ($s.Text -match '^(ALTER|CREATE|DROP)\b' -and $r.Output -match $benign) {
            Write-Output "  already there: $(($s.Text -split "`n")[0].Trim())"
            Record $s 'already'
        } else {
            throw ("A statement failed; the ones before it are recorded and the next run goes on from here:`n$($s.Text)`n$($r.Output)`n" +
                'If the database has these statements already (run by hand), say so once: apply-sql.ps1 -MarkApplied -Apply.')
        }
    }
    Write-Output "Applied $($pending.Count) statement(s)."
    $global:LASTEXITCODE = 0
} finally {
    Remove-Item $cnf, $work -ErrorAction SilentlyContinue
}
