<#
.SYNOPSIS
    Moves an install's three databases from the MySQL they sit in to the portable MySQL of the install (<install>\mysql), checking every table.

.DESCRIPTION
    For an install whose servers run on another MySQL (the installer's, port 3306) and has no <install>\mysql yet. The old MySQL is only read,
    never stopped, changed or emptied: going back is giving the three configuration files back (the .bak files setup-mysql.ps1 leaves).
    In order, and nothing before the dump is written:

      1. refuses while any Aion server runs (a character saved in the middle of a dump would be lost);
      2. unpacks the trimmed MySQL (package-mysql.ps1, from the official zip) into <install>\mysql;
      3. dumps aion_gs, aion_ls and aion_cs with the old settings (--hex-blob, utf8mb4) into <install>\backups\before-portable-<date>, with the
         CHECKSUM TABLE of every table; a dump without its "Dump completed" line stops everything here;
      4. setup-mysql.ps1 (new data folder, accounts, the empty schemas, the servers' configuration pointing at the new MySQL);
      5. restores the three dumps into the new MySQL;
      6. CHECKSUM TABLE again on the new side: the same tables, the same checksum, or the script says which are not and exits 1.

    MySQL (new) is left running and the old one untouched. Start the servers with start-server.ps1; stop the old MySQL only once the game has
    been tried (it is not in the way: another port).

.PARAMETER ServerRoot
    The install.

.PARAMETER Zip
    The official MySQL Community Server zip whose SHA-256 tools/mysql/portable-mysql.json names.

.PARAMETER Port
    The port of the new MySQL (3307 by default).
#>
param(
    [Parameter(Mandatory)][string]$ServerRoot,
    [string]$Zip,
    [int]$Port = 3307,
    [switch]$Resume
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mysql-common.ps1')
$ServerRoot = (Resolve-Path $ServerRoot).Path
$mysqlHome = Join-Path $ServerRoot 'mysql'
if (-not $Resume -and -not $Zip) { throw 'Pass -Zip (the official MySQL zip), or -Resume to go on from the dumps of an earlier run.' }

# ---- 1. nothing may be writing ------------------------------------------------------------------------------------------------------
$servers = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*com.aionemu.*' }
if ($servers) { throw "An Aion server runs (process $(($servers.ProcessId) -join ', ')): stop-server.ps1 first. Nothing was changed." }
if ($Resume) {
    # an earlier run got as far as the new MySQL (made, configured, running) and failed at the restore: its dumps and checksums are on disk
    if (-not (Test-PortListening $Port)) { throw "-Resume: the new MySQL does not answer on port ${Port}: start-mysql.ps1 -ServerRoot $ServerRoot first." }
} else {
    if (Test-Path $mysqlHome) { throw "$mysqlHome exists already: this install has been moved, or is being (-Resume goes on). Nothing was changed." }
    $Zip = (Resolve-Path $Zip).Path
    $pin = Get-Content (Join-Path $PSScriptRoot 'mysql\portable-mysql.json') -Raw | ConvertFrom-Json
    $hash = (Get-FileHash $Zip -Algorithm SHA256).Hash
    if ($hash -ne $pin.sha256.ToUpper()) { throw "$Zip is not the pinned zip (SHA-256 $hash, expected $($pin.sha256)). Nothing was changed." }
    if (Test-PortListening $Port) { throw "Something answers on port ${Port}. Pass another -Port. Nothing was changed." }
}

# what each server reads now
$old = @{}
if (-not $Resume) {
    foreach ($server in $script:AionServers) {
        $db = Read-DatabaseSettings $ServerRoot $server
        if (-not $db) { throw "No usable database.url for the $($server.Name) server." }
        if (-not (Test-PortListening $db.Port)) { throw "The old MySQL does not answer on port $($db.Port): start-mysql.ps1 first." }
        $old[$server.Name] = $db
    }
}

# ---- 2. the portable MySQL, unpacked ------------------------------------------------------------------------------------------------
if (-not $Resume) {
    Write-Host 'Unpacking the portable MySQL...' -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'package-mysql.ps1') -Source $Zip -OutDir $mysqlHome
}
if (-not (Test-Path (Join-Path $mysqlHome 'bin\mysqld.exe'))) { throw 'No mysqld.exe in the install''s mysql folder.' }
$bin = Join-Path $mysqlHome 'bin'
$mysql = Join-Path $bin 'mysql.exe'
$dump = Join-Path $bin 'mysqldump.exe'

# runs a client program with a password in the environment, native stderr read as text; returns code and output lines
function Invoke-Client([string]$Exe, [string]$Password, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $env:MYSQL_PWD = $Password
    try {
        $out = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
        [pscustomobject]@{ Code = $LASTEXITCODE; Lines = @($out) }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        $ErrorActionPreference = $previous
    }
}
# feeds a dump file to the client's standard input as bytes (no code page in between); PowerShell 5.1 has no '<'
function Restore-Dump($Db, [string]$File) {
    $info = New-Object Diagnostics.ProcessStartInfo $mysql
    $info.Arguments = "--protocol=TCP -h $($Db.Host) -P $($Db.Port) -u $($Db.User) --default-character-set=utf8mb4 $($Db.Schema)"
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $info.EnvironmentVariables['MYSQL_PWD'] = $Db.Password
    $process = [Diagnostics.Process]::Start($info)
    $errors = $process.StandardError.ReadToEndAsync()
    $null = $process.StandardOutput.ReadToEndAsync()
    $stream = [IO.File]::OpenRead($File)
    try { $stream.CopyTo($process.StandardInput.BaseStream) } finally { $stream.Close(); $process.StandardInput.Close() }
    $process.WaitForExit()
    [pscustomobject]@{ Code = $process.ExitCode; Output = $errors.Result }
}
function Get-Checksums($Db) {
    $common = @('--protocol=TCP', '-h', $Db.Host, '-P', "$($Db.Port)", '-u', $Db.User, '--batch', '--skip-column-names')
    $list = Invoke-Client $mysql $Db.Password ($common + @('-e', "SELECT table_name FROM information_schema.tables WHERE table_schema='$($Db.Schema)' AND table_type='BASE TABLE' ORDER BY table_name"))
    if ($list.Code -ne 0) { throw "Could not list the tables of $($Db.Schema): $($list.Lines -join ' ')" }
    $names = ($list.Lines | Where-Object { $_ }) | ForEach-Object { "``$($Db.Schema)``.``$_``" }
    $sums = Invoke-Client $mysql $Db.Password ($common + @('-e', "CHECKSUM TABLE $($names -join ', ')"))
    if ($sums.Code -ne 0) { throw "CHECKSUM TABLE failed on $($Db.Schema): $($sums.Lines -join ' ')" }
    $sums.Lines | Where-Object { $_ } | Sort-Object
}

# what the server read before setup-mysql.ps1 changed it: its database.properties, then its override file as it was (.bak), else as it is
function Read-OldSettings($Server) {
    $configDir = Join-Path (Join-Path $ServerRoot $Server.Folder) 'config'
    $override = Join-Path $configDir $Server.Override
    $files = @((Join-Path $configDir 'network\database.properties'))
    if (Test-Path "$override.bak") { $files += "$override.bak" }
    $props = @{}
    foreach ($file in $files) {
        foreach ($line in Get-Content $file -Encoding UTF8) { if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*?)\s*$') { $props[$Matches[1]] = $Matches[2] } }
    }
    if ($props['database.url'] -notmatch '^jdbc:mysql://([^:/?]+)(?::(\d+))?/([^?]+)') { throw "No old database.url for the $($Server.Name) server." }
    [pscustomobject]@{ Host = $Matches[1]; Port = $(if ($Matches[2]) { [int]$Matches[2] } else { 3306 }); Schema = $Matches[3]; User = $props['database.user']; Password = $props['database.password'] }
}

# differences between two checksum lists; a table only the new side has and the old never did (the ledger apply-sql.ps1 adds) is not one
function Get-Differences($Before, $After) {
    $known = @($Before | ForEach-Object { ($_ -split "`t")[0] })
    Compare-Object $Before $After | Where-Object { -not ($_.SideIndicator -eq '=>' -and ($known -notcontains (($_.InputObject -split "`t")[0]))) }
}

# Copies the FLOAT and DOUBLE columns of one table, exactly, from the old database into the new one; $false when it has none.
function Repair-Floats($OldDb, $NewDb, [string]$Table) {
    $common = @('--protocol=TCP', '--batch', '--skip-column-names', '--raw')
    $info = Invoke-Client $mysql $NewDb.Password ($common + @('-h', $NewDb.Host, '-P', "$($NewDb.Port)", '-u', $NewDb.User, '-e',
        "SELECT column_name, data_type, column_key FROM information_schema.columns WHERE table_schema='$($NewDb.Schema)' AND table_name='$Table' ORDER BY ordinal_position"))
    $columns = $info.Lines | Where-Object { $_ } | ForEach-Object { $p = $_ -split "`t"; [pscustomobject]@{ Name = $p[0]; Type = $p[1]; Key = $p[2] } }
    $floats = @($columns | Where-Object { $_.Type -in 'float', 'double' })
    $keys = @($columns | Where-Object { $_.Key -eq 'PRI' })
    if ($floats.Count -eq 0) { return $false }
    if ($keys.Count -eq 0) { throw "$Table has float columns and no primary key: they cannot be copied by key." }
    $set = ($floats | ForEach-Object { "'``$($_.Name)``=', IFNULL(CAST(``$($_.Name)``+0e0 AS CHAR),'NULL')" }) -join ", ',',"
    $where = ($keys | ForEach-Object { "'``$($_.Name)``=', QUOTE(``$($_.Name)``)" }) -join ", ' AND ',"
    $select = "SELECT CONCAT('UPDATE ``$Table`` SET ', $set, ' WHERE ', $where, ';') FROM ``$Table``"
    $read = Invoke-Client $mysql $OldDb.Password ($common + @('-h', $OldDb.Host, '-P', "$($OldDb.Port)", '-u', $OldDb.User, $OldDb.Schema, '-e', $select))
    if ($read.Code -ne 0) { throw "Reading the exact floats of $Table failed: $($read.Lines -join ' ')" }
    $file = Join-Path $env:TEMP "aion-floats-$PID-$Table.sql"
    [IO.File]::WriteAllLines($file, [string[]]@($read.Lines | Where-Object { $_ }), (New-Object Text.UTF8Encoding($false)))
    try { $r = Restore-Dump $NewDb $file } finally { Remove-Item $file -ErrorAction SilentlyContinue }
    if ($r.Code -ne 0) { throw "Writing the exact floats of $Table failed: $($r.Output)" }
    return $true
}

# ---- 3. the old databases, dumped and checksummed -------------------------------------------------------------------------------------
$before = @{}
if ($Resume) {
    $folder = (Get-ChildItem (Join-Path $ServerRoot 'backups') -Directory -Filter 'before-portable-*' | Sort-Object Name -Descending | Select-Object -First 1).FullName
    if (-not $folder) { throw '-Resume: no before-portable-* folder under backups.' }
    Write-Host "Resuming from $folder" -ForegroundColor Cyan
    foreach ($server in $script:AionServers) {
        $schema = (Read-DatabaseSettings $ServerRoot $server).Schema
        foreach ($f in "$schema.sql", "$schema.checksums.txt") { if (-not (Test-Path (Join-Path $folder $f))) { throw "-Resume: $f is missing from $folder." } }
        if (-not (Select-String -Path (Join-Path $folder "$schema.sql") -Pattern '^-- Dump completed' -Quiet)) { throw "-Resume: the dump of $schema is incomplete." }
        $before[$server.Name] = @(Get-Content (Join-Path $folder "$schema.checksums.txt") | Where-Object { $_ } | Sort-Object)
    }
} else {
$folder = Join-Path (Join-Path $ServerRoot 'backups') ('before-portable-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force $folder | Out-Null
foreach ($server in $script:AionServers) {
    $db = $old[$server.Name]
    Write-Host "Dumping $($db.Schema)..." -ForegroundColor Cyan
    $file = Join-Path $folder "$($db.Schema).sql"
    $r = Invoke-Client $dump $db.Password @('--protocol=TCP', '-h', $db.Host, '-P', "$($db.Port)", '-u', $db.User, '--default-character-set=utf8mb4', '--hex-blob',
        '--set-gtid-purged=OFF', '--skip-lock-tables', '--no-tablespaces', '--routines', "--result-file=$file", $db.Schema)
    if ($r.Code -ne 0 -or -not (Select-String -Path $file -Pattern '^-- Dump completed' -Quiet)) { throw "The dump of $($db.Schema) is incomplete ($($r.Lines -join ' ')): nothing was changed but $mysqlHome and $folder." }
    $before[$server.Name] = Get-Checksums $db
    Set-Content (Join-Path $folder "$($db.Schema).checksums.txt") -Value $before[$server.Name] -Encoding ascii
    Write-Host ("  {0}: {1:N1} MB, {2} tables" -f $db.Schema, ((Get-Item $file).Length / 1MB), $before[$server.Name].Count)
}

# ---- 4. the new MySQL ---------------------------------------------------------------------------------------------------------------
& (Join-Path $PSScriptRoot 'setup-mysql.ps1') -ServerRoot $ServerRoot -Port $Port
if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) { throw 'setup-mysql.ps1 failed.' }
}

# ---- 5 and 6. restored, then compared ----------------------------------------------------------------------------------------------------
$bad = @()
foreach ($server in $script:AionServers) {
    $db = Read-DatabaseSettings $ServerRoot $server
    if ($db.Port -ne $Port) { throw "The $($server.Name) server's configuration does not point at port $Port." }
    Write-Host "Restoring $($db.Schema)..." -ForegroundColor Cyan
    $r = Restore-Dump $db (Join-Path $folder "$($db.Schema).sql")
    if ($r.Code -ne 0) { throw "Restoring $($db.Schema) failed: $($r.Output)" }
    $after = Get-Checksums $db
    $diff = Get-Differences $before[$server.Name] $after
    if ($diff) {
        # mysqldump writes a FLOAT with six digits (1081.015380859375 becomes 1081.02): the tables that differ and hold such columns get their exact
        # values copied from the old database, by primary key, then are compared again
        $tables = $diff | ForEach-Object { ($_.InputObject -split "`t")[0] -replace '^[^.]+\.', '' } | Sort-Object -Unique
        $oldDb = Read-OldSettings $server
        foreach ($table in $tables) {
            if (Repair-Floats $oldDb $db $table) { Write-Host "  $($db.Schema).${table}: exact float values copied from the old database" -ForegroundColor Yellow }
        }
        $after = Get-Checksums $db
        $diff = Get-Differences $before[$server.Name] $after
    }
    if ($diff) { $bad += $diff | ForEach-Object { "$($db.Schema): $($_.SideIndicator) $($_.InputObject)" } }
    else { Write-Host ("  {0}: {1} tables, every checksum the same" -f $db.Schema, $after.Count) -ForegroundColor Green }
}
if ($bad.Count -gt 0) {
    $bad | ForEach-Object { Write-Host "  DIFFERENT $_" -ForegroundColor Red }
    Write-Host "The old MySQL still holds everything. To go back, copy each config\my*.properties.bak over its file (and delete myls.properties). Dumps: $folder" -ForegroundColor Yellow
    exit 1
}
Write-Host "Done: all three databases are in the portable MySQL on port $Port, table for table. The old MySQL is untouched. Dumps: $folder" -ForegroundColor Green
