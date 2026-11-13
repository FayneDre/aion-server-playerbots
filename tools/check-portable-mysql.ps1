<#
.SYNOPSIS
    Tries the install's portable MySQL end to end on a throwaway install, and says PASS or FAIL for each thing it checks. Touches nothing that exists.

.DESCRIPTION
    Run it after a change to package-mysql.ps1, setup-mysql.ps1, the three MySQL scripts or my-portable.ini / my-light.ini, after an upstream merge that
    touches the schemas, and before the pinned MySQL version (tools/mysql/portable-mysql.json) is ever moved.

    Everything happens in a new folder of the temp directory, on a port nobody uses (3317 by default; 3306, the usual MySQL's, is refused), from the
    repository's own configuration and schemas: no install, no database, no running server of this machine is read or written. The folder is removed at the end
    (-Keep leaves it), and the MySQL it started is stopped with mysqladmin, never killed. In order:

      1. the official zip is the pinned one (SHA-256 of tools/mysql/portable-mysql.json; downloaded to a cache in the temp directory when -Zip is not given);
      2. package-mysql.ps1 trims it (Oracle's signature, a size ceiling, a zip written with / paths);
      3. setup-mysql.ps1 makes the database: the table counts are those of the schemas plus the ledger, the accounts exist, the account of the servers can stop MySQL;
      4. the servers' own JDBC driver connects with that account and runs the statements the DAOs use (skipped without Java or the driver in ~/.m2);
      5. a dump of the three databases, a clean stop ("Shutdown complete"), a start from cold on the port the servers' configuration names, a second start that
         leaves it alone, a clean stop again;
      6. setup-mysql.ps1 refuses a second run on the same install, and a port that answers.

    The exit code is 1 when anything failed.

.PARAMETER Zip
    The official zip, when it is already on disk (it is still checked against the pinned SHA-256).

.PARAMETER Port
    The port of the throwaway MySQL.

.PARAMETER Keep
    Leave the throwaway install in place, and say where.

.PARAMETER SkipProbe
    Do not run the JDBC probe.

.EXAMPLE
    .\tools\check-portable-mysql.ps1
#>
param(
    [string]$Zip,
    [int]$Port = 3317,
    [switch]$Keep,
    [switch]$SkipProbe
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'mysql-common.ps1')
$repo = Split-Path $PSScriptRoot -Parent
$pin = Get-Content (Join-Path $PSScriptRoot 'mysql\portable-mysql.json') -Raw | ConvertFrom-Json

$script:failures = 0
function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    if ($Ok) { Write-Host "  PASS  $Name" -ForegroundColor Green }
    else { Write-Host "  FAIL  $Name $Detail" -ForegroundColor Red; $script:failures++ }
}
# what a script said through Write-Host (the information stream of 5.0 and later), and whether it threw
function Invoke-Quiet([scriptblock]$Block) {
    try { $text = (& $Block *>&1 | Out-String); [pscustomobject]@{ Ok = $true; Text = $text } }
    catch { [pscustomobject]@{ Ok = $false; Text = $_.Exception.Message } }
}

if ($Port -eq 3306) { throw '3306 is the usual MySQL''s port: this check never goes near it. Pass another -Port.' }
if (Test-PortListening $Port) { throw "Something already answers on port ${Port}: pass another -Port." }

$work = Join-Path $env:TEMP ("aion-check-mysql-$PID")
$inst = Join-Path $work 'install'
New-Item -ItemType Directory -Force $work | Out-Null
Write-Host "Throwaway install: $inst (port $Port)" -ForegroundColor Cyan

try {
    # ---- 1. the pinned zip ----------------------------------------------------------------------------------------------------------------
    Write-Host '1. the official zip' -ForegroundColor Cyan
    if (-not $Zip) {
        $cache = Join-Path $env:TEMP 'aion-mysql-cache'
        New-Item -ItemType Directory -Force $cache | Out-Null
        $Zip = Join-Path $cache (Split-Path $pin.url -Leaf)
        if (-not (Test-Path $Zip) -or (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower() -ne $pin.sha256) {
            Write-Host "  downloading $($pin.url)"
            $previousProgress = $ProgressPreference
            $ProgressPreference = 'SilentlyContinue'
            try { Invoke-WebRequest $pin.url -OutFile $Zip -UseBasicParsing } finally { $ProgressPreference = $previousProgress }
        }
    }
    $hash = (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower()
    Check "the zip is the pinned one (MySQL $($pin.version))" ($hash -eq $pin.sha256) "got $hash"
    if ($hash -ne $pin.sha256) { throw 'The zip is not the pinned one: nothing else is tried.' }

    # ---- 2. the trimmed MySQL -----------------------------------------------------------------------------------------------------------
    Write-Host '2. package-mysql.ps1' -ForegroundColor Cyan
    New-Item -ItemType Directory -Force $inst | Out-Null
    $zipOut = Join-Path $work 'trimmed.zip'
    $made = Invoke-Quiet { & (Join-Path $PSScriptRoot 'package-mysql.ps1') -Source $Zip -OutDir (Join-Path $inst 'mysql') -ZipOut $zipOut }
    Check 'trimmed, with an Oracle signature' ($made.Ok -and $made.Text -match 'Signatures: Oracle, valid') $made.Text
    $bytes = (Get-ChildItem (Join-Path $inst 'mysql') -Recurse -File | Measure-Object Length -Sum).Sum
    Check ("unpacked size under 150 MB ({0:N0} MB)" -f ($bytes / 1MB)) ($bytes -lt 150MB)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($zipOut)
    $slashes = @($archive.Entries | Where-Object { $_.FullName -match '\\' }).Count
    $entries = $archive.Entries.Count
    $archive.Dispose()
    Check "the zip has no backslash in its $entries entries" ($slashes -eq 0) "($slashes with one)"
    Check ("the zip is under 60 MB ({0:N0} MB)" -f ((Get-Item $zipOut).Length / 1MB)) ((Get-Item $zipOut).Length -lt 60MB)

    # ---- 3. the database ------------------------------------------------------------------------------------------------------------------
    Write-Host '3. setup-mysql.ps1' -ForegroundColor Cyan
    # the repository's own configuration: no install of this machine is read
    foreach ($server in $script:AionServers) {
        $to = Join-Path $inst "$($server.Folder)\config\network"
        New-Item -ItemType Directory -Force $to | Out-Null
        Copy-Item (Join-Path $repo "$($server.Folder)\config\network\database.properties") $to
    }
    Copy-Item (Join-Path $repo 'game-server\config\network\network.properties') (Join-Path $inst 'game-server\config\network')
    $setup = Invoke-Quiet { & (Join-Path $PSScriptRoot 'setup-mysql.ps1') -ServerRoot $inst -Port $Port }
    Check 'the database is made' ($setup.Ok -and $setup.Text -match "MySQL runs on port $Port") $setup.Text
    if (-not $setup.Ok) { throw 'setup-mysql.ps1 failed: nothing else is tried.' }

    $game = Read-DatabaseSettings $inst ($script:AionServers | Where-Object Name -eq 'game')
    $mysql = Join-Path $inst 'mysql\bin\mysql.exe'
    function Invoke-Aion([string]$Sql) {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $env:MYSQL_PWD = $game.Password
        try { (& $mysql --protocol=TCP -h 127.0.0.1 -P $Port -u $game.User --batch --skip-column-names -e $Sql 2>&1 | ForEach-Object { "$_" }) -join "`n" }
        finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue; $ErrorActionPreference = $previous }
    }
    # the schemas' tables, and the ledger apply-sql.ps1 adds to each
    foreach ($case in @(@('aion_gs', 'game-server\sql\aion_gs.sql'), @('aion_ls', 'login-server\sql\aion_ls.sql'), @('aion_cs', 'chat-server\sql\aion_cs.sql'))) {
        $expected = 1 + @(Select-String -Path (Join-Path $repo $case[1]) -Pattern '(?i)^\s*create table').Count
        $actual = (Invoke-Aion "select count(*) from information_schema.tables where table_schema='$($case[0])'").Trim()
        Check "$($case[0]) has $expected tables (its schema plus the ledger)" ($actual -eq "$expected") "has $actual"
    }
    Check 'the game server is entered at the login server' ((Invoke-Aion 'select count(*) from aion_ls.gameservers').Trim() -eq '1')
    Check 'the servers'' account can stop MySQL (SHUTDOWN)' ((Invoke-Aion 'show grants') -match 'SHUTDOWN')
    Check 'root.cnf exists, and the override files name the portable port' (
        (Test-Path (Join-Path $inst 'mysql\root.cnf')) -and ((Get-Content (Join-Path $inst 'game-server\config\mygs.properties') -Raw) -match "localhost:$Port/aion_gs"))
    $ini = Get-Content (Join-Path $inst 'mysql\my.ini') -Raw
    Check 'MySQL answers this machine only, with the light settings' ($ini -match 'bind-address=127\.0\.0\.1' -and $ini -match 'performance_schema=OFF' -and $ini -match 'skip-log-bin')
    $resident = [int]((Get-Process mysqld -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$inst*" } | Measure-Object WorkingSet64 -Sum).Sum / 1MB)
    Check "resident memory under 400 MB ($resident MB)" ($resident -gt 0 -and $resident -lt 400)

    # ---- 4. the servers' driver -----------------------------------------------------------------------------------------------------------
    Write-Host '4. the JDBC driver' -ForegroundColor Cyan
    $jar = $null
    $m2 = Join-Path $env:USERPROFILE '.m2\repository\com\mysql\mysql-connector-j'
    if (Test-Path $m2) {
        $jar = Get-ChildItem $m2 -Recurse -Filter 'mysql-connector-j-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            Sort-Object { try { [version]($_.Directory.Name) } catch { [version]'0.0' } } | Select-Object -Last 1
    }
    if ($SkipProbe -or -not $jar -or -not (Get-Command java.exe -ErrorAction SilentlyContinue)) {
        Write-Host '  skipped (no -SkipProbe, driver in ~/.m2 and java.exe on the PATH needed)' -ForegroundColor Yellow
    } else {
        $probe = Join-Path $work 'Probe.java'
        # written without a byte order mark: Set-Content -Encoding UTF8 of Windows PowerShell 5.1 adds one, which javac refuses at the head of a source
        [IO.File]::WriteAllText($probe, @'
import java.sql.*;
public class Probe {
	public static void main(String[] a) throws Exception {
		try (Connection c = DriverManager.getConnection(a[0], a[1], a[2])) {
			System.out.println("server=" + c.getMetaData().getDatabaseProductVersion());
			try (Statement s = c.createStatement()) {
				s.execute("create table t_probe (id int primary key, v int)");
				s.execute("insert into t_probe values (1,1) on duplicate key update v=v+1");
				s.execute("insert into t_probe values (1,1) on duplicate key update v=v+1");
				s.execute("replace into t_probe values (2,5)");
				s.execute("insert ignore into t_probe values (2,9)");
				try (ResultSet r = s.executeQuery("select group_concat(v order by id) from t_probe")) { r.next(); System.out.println("upserts=" + r.getString(1)); }
				s.execute("drop table t_probe");
			}
			try (PreparedStatement p = c.prepareStatement("select table_name from information_schema.tables where table_schema=? limit ?")) {
				p.setString(1, "aion_gs"); p.setInt(2, 3);
				int n = 0; try (ResultSet r = p.executeQuery()) { while (r.next()) n++; }
				System.out.println("prepared=" + n);
			}
			c.setAutoCommit(false);
			try (Statement s = c.createStatement()) { s.execute("select 1"); }
			c.commit();
			System.out.println("transactions=ok");
			try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("show session status like 'Ssl_cipher'")) { r.next(); System.out.println("tls=" + (r.getString(2).isEmpty() ? "none" : "yes")); }
		}
	}
}
'@, (New-Object Text.UTF8Encoding($false)))
        $url = $game.Host + ':' + $Port
        $jdbc = "jdbc:mysql://${url}/aion_gs?serverTimezone=Europe/Paris&characterEncoding=UTF-8"
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $said = & java.exe -cp $jar.FullName $probe $jdbc $game.User $game.Password 2>&1 | ForEach-Object { "$_" }
        $ErrorActionPreference = $previous
        $said = $said -join "`n"
        Check "the driver ($($jar.Directory.Name)) connects, upserts, prepares, commits, over TLS" ($said -match 'upserts=2,5' -and $said -match 'prepared=3' -and $said -match 'transactions=ok' -and $said -match 'tls=yes') $said
    }

    # ---- 5. the cycle -------------------------------------------------------------------------------------------------------------------
    Write-Host '5. dump, stop, start from cold, stop' -ForegroundColor Cyan
    $dump = Invoke-Quiet { & (Join-Path $PSScriptRoot 'backup-db.ps1') -ServerRoot $inst }
    $backup = Get-ChildItem (Join-Path $inst 'backups') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    $dumps = if ($backup) { @(Get-ChildItem $backup.FullName -Filter *.sql) } else { @() }
    Check 'a dump of the three databases, each complete' ($dump.Ok -and $dumps.Count -eq 3) $dump.Text

    $stopped = Invoke-Quiet { & (Join-Path $PSScriptRoot 'stop-mysql.ps1') -ServerRoot $inst }
    $log = Join-Path $inst 'mysql\data\mysql.err'
    $gone = @(Get-Process mysqld -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$inst*" }).Count -eq 0
    Check 'MySQL stops cleanly (Shutdown complete, no process left)' ($stopped.Ok -and $gone -and (Select-String -Path $log -Pattern 'Shutdown complete' -Quiet)) $stopped.Text
    Check 'the port is free again' (-not (Test-PortListening $Port))

    $started = Invoke-Quiet { & (Join-Path $PSScriptRoot 'start-mysql.ps1') -ServerRoot $inst }
    Check 'it starts from cold, on the port the servers'' configuration names' ($started.Ok -and (Test-PortListening $Port)) $started.Text
    $again = Invoke-Quiet { & (Join-Path $PSScriptRoot 'start-mysql.ps1') -ServerRoot $inst }
    Check 'a second start leaves the running MySQL alone' ($again.Ok -and $again.Text -match 'already running')
    Check 'the data is still there after the restart' ((Invoke-Aion "select count(*) from information_schema.tables where table_schema='aion_gs'").Trim() -ne '0')
    $stopped = Invoke-Quiet { & (Join-Path $PSScriptRoot 'stop-mysql.ps1') -ServerRoot $inst }
    Check 'and it stops cleanly again' ($stopped.Ok -and -not (Test-PortListening $Port)) $stopped.Text

    # ---- 6. what must be refused --------------------------------------------------------------------------------------------------------
    Write-Host '6. refusals' -ForegroundColor Cyan
    $second = Invoke-Quiet { & (Join-Path $PSScriptRoot 'setup-mysql.ps1') -ServerRoot $inst -Port $Port }
    Check 'a second setup on the same install is refused' (-not $second.Ok -and $second.Text -match 'not empty')
    $listener = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, $Port)
    $listener.Start()
    try {
        $inst2 = Join-Path $work 'install2'
        foreach ($server in $script:AionServers) {
            $to = Join-Path $inst2 "$($server.Folder)\config\network"
            New-Item -ItemType Directory -Force $to | Out-Null
            Copy-Item (Join-Path $repo "$($server.Folder)\config\network\database.properties") $to
        }
        # only the programs: a second install with nothing in it yet, whose data folder must never be made
        New-Item -ItemType Directory -Force (Join-Path $inst2 'mysql') | Out-Null
        Copy-Item (Join-Path $inst 'mysql\bin') (Join-Path $inst2 'mysql\bin') -Recurse
        $busy = Invoke-Quiet { & (Join-Path $PSScriptRoot 'setup-mysql.ps1') -ServerRoot $inst2 -Port $Port }
        Check 'a port that already answers is refused, and nothing is made' (-not $busy.Ok -and $busy.Text -match 'already answers' -and -not (Test-Path (Join-Path $inst2 'mysql\data')))
    } finally {
        $listener.Stop()
    }
} catch {
    Write-Host "  FAIL  $($_.Exception.Message)" -ForegroundColor Red
    $script:failures++
} finally {
    # a MySQL still up is stopped the clean way (the root account of the throwaway install), never killed
    $left = @(Get-Process mysqld -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$work*" })
    if ($left.Count -gt 0) {
        $admin = Join-Path $inst 'mysql\bin\mysqladmin.exe'
        $rootFile = Join-Path $inst 'mysql\root.cnf'
        if ((Test-Path $admin) -and (Test-Path $rootFile)) {
            $previous = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            & $admin "--defaults-extra-file=$rootFile" shutdown 2>&1 | Out-Null
            $ErrorActionPreference = $previous
            $deadline = (Get-Date).AddSeconds(60)
            while ((Get-Date) -lt $deadline -and @(Get-Process mysqld -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$work*" }).Count -gt 0) { Start-Sleep -Milliseconds 500 }
        }
        if (@(Get-Process mysqld -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$work*" }).Count -gt 0) {
            Write-Host "  the throwaway MySQL is still running: $work is left in place, stop it by hand." -ForegroundColor Yellow
            $Keep = $true
        }
    }
    if ($Keep) { Write-Host "Left in place: $work" -ForegroundColor Yellow }
    elseif (Test-Path $work) { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($script:failures -gt 0) {
    Write-Host "$($script:failures) check(s) failed." -ForegroundColor Red
    exit 1
}
Write-Host 'All checks passed.' -ForegroundColor Green
exit 0
