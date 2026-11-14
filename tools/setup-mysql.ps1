<#
.SYNOPSIS
    Makes the database of an install from nothing, in the portable MySQL it carries: data folder, accounts, the three servers' databases and their
    configuration. Refuses anything that already holds data.

.DESCRIPTION
    For an install whose <install>\mysql holds the trimmed MySQL (package-mysql.ps1) and nothing else: no data folder, no my.ini. In order:

      1. my.ini from tools/mysql/my-portable.ini and my-light.ini (this port, this folder, the memory settings);
      2. the data folder, made by mysqld itself (--initialize-insecure);
      3. MySQL started (start-mysql.ps1), on 127.0.0.1 only;
      4. root given a random password, kept in <install>\mysql\root.cnf (readable by this user only), for what an administrator has to do later;
      5. the servers' account, 'aion'@'localhost', with a random password and what it needs: all rights on the three databases and SHUTDOWN, so that
         stop-mysql.ps1 can stop MySQL cleanly; it is written, with the database.url of each server, into the override files of the three servers
         (mygs.properties, myls.properties, mycs.properties), which a deploy never touches; a file that exists is copied to .bak first;
      6. apply-sql.ps1 -Create for the game, login and chat databases (the schema, the ledger, the game server entered at the login server).

    MySQL is left running. Nothing is done when there is any sign of an earlier database: an existing data folder or my.ini, a port that already
    answers, a database that has tables (apply-sql.ps1 refuses those itself). It never reads or removes a character.

.PARAMETER ServerRoot
    The install. Defaults to the install this script sits in (<install>\tools), else AION_SERVER_HOME.

.PARAMETER Port
    The port for this MySQL. 3307 by default, so that it never meets a MySQL on 3306.

.PARAMETER MysqlBin
    The folder of the MySQL programs, when they are not in <install>\mysql\bin.

.EXAMPLE
    .\tools\setup-mysql.ps1 -ServerRoot D:\Games\Echoes\server
#>
param(
    [string]$ServerRoot,
    [int]$Port = 3307,
    [string]$MysqlBin
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mysql-common.ps1')

if (-not $ServerRoot) {
    $ownInstall = Split-Path $PSScriptRoot -Parent
    $ServerRoot = if (Test-Path (Join-Path $ownInstall 'game-server\start.bat')) { $ownInstall } else { $env:AION_SERVER_HOME }
}
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }
$ServerRoot = (Resolve-Path $ServerRoot).Path
Write-Host "Install: $ServerRoot"

$mysqlHome = Join-Path $ServerRoot 'mysql'
# the option file is plain ASCII, which MySQL reads in the system's code page: a letter outside it in the path would be read wrong
if ($mysqlHome -match '[^\x00-\x7F]') { throw "The path $mysqlHome has a letter outside plain ASCII (an accent): MySQL would read it wrong. Install under a path without one." }
$bin = if ($MysqlBin) { $MysqlBin } else { Join-Path $mysqlHome 'bin' }
foreach ($tool in 'mysqld.exe', 'mysql.exe', 'mysqladmin.exe') {
    if (-not (Test-Path (Join-Path $bin $tool))) { throw "No $tool in ${bin}: unpack the trimmed MySQL (package-mysql.ps1) into ${mysqlHome} first." }
}

# ---- nothing here may already hold a database --------------------------------------------------------------------------------------------
$dataDir = Join-Path $mysqlHome 'data'
$iniFile = Join-Path $mysqlHome 'my.ini'
if ((Test-Path $dataDir) -and (Get-ChildItem $dataDir -Force -ErrorAction SilentlyContinue | Select-Object -First 1)) { throw "$dataDir is not empty: this makes a database from nothing and never goes near one that exists." }
if (Test-Path $iniFile) { throw "$iniFile exists already: this install has been set up. Nothing was changed." }
if (Test-PortListening $Port) { throw "Something already answers on port ${Port}: another MySQL is running there. Pass another -Port. Nothing was changed." }

$templates = Join-Path $PSScriptRoot 'mysql'
foreach ($file in 'my-portable.ini', 'my-light.ini') { if (-not (Test-Path (Join-Path $templates $file))) { throw "Missing $templates\$file." } }
$servers = $script:AionServers
foreach ($server in $servers) {
    $folder = Join-Path $ServerRoot $server.Folder
    if (-not (Test-Path (Join-Path $folder 'config\network\database.properties'))) { throw "No $($server.Folder)\config\network\database.properties in $ServerRoot." }
}

function New-RandomPassword([int]$Length = 24) {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'   # letters and digits only: safe in a properties file and in SQL
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $chars = New-Object Text.StringBuilder
    $buffer = New-Object byte[] 1
    while ($chars.Length -lt $Length) {
        $rng.GetBytes($buffer)
        if ($buffer[0] -lt 248) { [void]$chars.Append($alphabet[$buffer[0] % 62]) }   # 248 = 4 x 62: no value is likelier than another
    }
    $rng.Dispose()
    $chars.ToString()
}

# a file only the person who made it can read
function Protect-File([string]$Path) {
    & icacls.exe $Path /inheritance:r /grant:r "$($env:USERNAME):(R,W)" 2>&1 | Out-Null
}

# sets keys in a .properties file, leaving every other line as it is; the file copied to .bak first when it exists
function Set-Properties([string]$Path, [hashtable]$Values) {
    $lines = New-Object Collections.Generic.List[string]
    if (Test-Path $Path) {
        Copy-Item $Path "$Path.bak" -Force
        foreach ($line in Get-Content $Path -Encoding UTF8) { $lines.Add($line) }
    }
    foreach ($key in $Values.Keys) {
        $set = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match "^\s*$([regex]::Escape($key))\s*=") { $lines[$i] = "$key = $($Values[$key])"; $set = $true }
        }
        if (-not $set) { $lines.Add("$key = $($Values[$key])") }
    }
    [IO.File]::WriteAllLines($Path, $lines, (New-Object Text.UTF8Encoding($false)))
}

# ---- 1. my.ini ----------------------------------------------------------------------------------------------------------------------------
New-Item -ItemType Directory -Force $mysqlHome | Out-Null
$forward = { param($p) $p.Replace('\', '/') }
$base = [IO.File]::ReadAllText((Join-Path $templates 'my-portable.ini'))
$base = $base.Replace('@PORT@', "$Port").Replace('@BASEDIR@', (& $forward $mysqlHome)).Replace('@DATADIR@', (& $forward $dataDir))
$lightLines = [IO.File]::ReadAllLines((Join-Path $templates 'my-light.ini'))
$afterHeader = $false
$light = foreach ($line in $lightLines) {
    if ($line -match '^\s*\[mysqld\]') { $afterHeader = $true; continue }
    if ($afterHeader -and $line.Trim() -and $line -notmatch '^\s*#') { $line }
}
[IO.File]::WriteAllText($iniFile, $base.TrimEnd() + "`r`n`r`n# the light settings (tools/mysql/my-light.ini)`r`n" + ($light -join "`r`n") + "`r`n", (New-Object Text.ASCIIEncoding))
Write-Host "my.ini written ($Port, bind 127.0.0.1)." -ForegroundColor Green

# ---- 2. the data folder ------------------------------------------------------------------------------------------------------------------
Write-Host 'Making the data folder...' -ForegroundColor Cyan
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'   # mysqld writes its progress to stderr, which 5.1 turns into an error under 'Stop'
& (Join-Path $bin 'mysqld.exe') "--defaults-file=$iniFile" --initialize-insecure 2>&1 | Out-Null
$initCode = $LASTEXITCODE
$ErrorActionPreference = $previous
if ($initCode -ne 0 -or -not (Test-Path (Join-Path $dataDir 'mysql.ibd'))) {
    $log = Join-Path $dataDir 'mysql.err'
    throw "mysqld could not make its data folder (exit $initCode). $(if (Test-Path $log) { (Get-Content $log -Tail 8) -join ' | ' })"
}

# ---- 3. started ----------------------------------------------------------------------------------------------------------------------
& (Join-Path $PSScriptRoot 'start-mysql.ps1') -ServerRoot $ServerRoot -DefaultsFile $iniFile -MysqlBin $bin -Port $Port

# ---- 4 and 5. the accounts ---------------------------------------------------------------------------------------------------------
$mysql = Join-Path $bin 'mysql.exe'
function Invoke-Root([string]$Password, [string]$Statement) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $env:MYSQL_PWD = $Password
    try {
        $out = & $mysql --protocol=TCP -h 127.0.0.1 -P $Port -u root --batch --skip-column-names -e $Statement 2>&1 | ForEach-Object { "$_" }
        [pscustomobject]@{ Code = $LASTEXITCODE; Output = ($out -join "`n") }
    } finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        $ErrorActionPreference = $previous
    }
}

$rootPassword = New-RandomPassword
$aionPassword = New-RandomPassword
$databases = @('aion_gs', 'aion_ls', 'aion_cs')
$grants = ($databases | ForEach-Object { "GRANT ALL PRIVILEGES ON ``$_``.* TO 'aion'@'localhost';" }) -join ' '
$made = Invoke-Root '' ("ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword'; " +
    "CREATE USER 'aion'@'localhost' IDENTIFIED BY '$aionPassword'; " +
    (($databases | ForEach-Object { "CREATE DATABASE IF NOT EXISTS ``$_`` DEFAULT CHARACTER SET utf8mb4;" }) -join ' ') + " $grants " +
    "GRANT SHUTDOWN ON *.* TO 'aion'@'localhost'; FLUSH PRIVILEGES;")
if ($made.Code -ne 0) { throw "Could not make the accounts: $($made.Output)" }

$rootFile = Join-Path $mysqlHome 'root.cnf'
Set-Content $rootFile -Encoding ascii -Value @('[client]', 'user=root', "password=$rootPassword", 'host=127.0.0.1', "port=$Port")
Protect-File $rootFile
Write-Host "Accounts made: root (password in $rootFile), aion (password in the servers' override files)." -ForegroundColor Green

foreach ($server in $servers) {
    $props = Read-DatabaseSettings $ServerRoot $server
    if (-not $props) { throw "No usable database.url for the $($server.Name) server." }
    # the server's own url, only its port changed: the time zone and character set it asks for stay as they are
    $defaultsFile = Join-Path (Join-Path $ServerRoot $server.Folder) 'config\network\database.properties'
    $url = (Get-Content $defaultsFile -Encoding UTF8 | Where-Object { $_ -match '^\s*database\.url\s*=' } | Select-Object -First 1) -replace '^\s*database\.url\s*=\s*', ''
    $url = $url.Trim() -replace '(?<=jdbc:mysql://[^:/?]+):\d+', ":$Port"
    Set-Properties (Join-Path (Join-Path $ServerRoot $server.Folder) "config\$($server.Override)") @{
        'database.url' = $url; 'database.user' = 'aion'; 'database.password' = $aionPassword
    }
}
Write-Host 'The three servers now read their database from the new MySQL.' -ForegroundColor Green

# ---- 6. the three databases --------------------------------------------------------------------------------------------------------
foreach ($server in 'game', 'login', 'chat') {
    & (Join-Path $PSScriptRoot 'apply-sql.ps1') -ServerRoot $ServerRoot -Server $server -Create -Apply -MysqlBin $bin
    if ($LASTEXITCODE -ne 0) { throw "apply-sql.ps1 -Create failed for the $server server." }
}
Write-Host "Done: MySQL runs on port $Port, the databases are made. Stop it with stop-mysql.ps1." -ForegroundColor Green
