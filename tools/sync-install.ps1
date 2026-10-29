<#
.SYNOPSIS
    Brings the installed servers in line with the repository: the libraries and the servers' own jars, and after an upstream merge the data and
    configuration files upstream changed and the SQL it added (through apply-sql.ps1). Shows what it would do; changes nothing without -Apply.

.DESCRIPTION
    deploy.ps1 used to copy the jars of the game and chat servers and the handlers, and nothing else, so a merge that moves to new libraries (Netty 4.2,
    jakarta XML binding, newer Hikari, MySQL connector, logback), changes the static data or the configuration, or adds columns to the database left an
    install that could not start. The install is a copy made once, from the repository of that day, so what changed since is a three way question:

    - a library the build needs and the install lacks is added; one the build no longer needs is removed; and the servers' own jars the others load
      (commons in the login and chat servers, the login server's jar) are copied when they differ;
    - a file the install still holds as the repository had it (-From) is replaced by the new one (-To), or removed if upstream removed it;
    - a file the install holds otherwise, edited here, is left alone and named, for a hand merge;
    - the statements of game-server/sql/update.sql the database has not had yet are listed, and with -Apply run once each after a backup
      (apply-sql.ps1, which deploy.ps1 also runs: this is the same step, shown here with the rest).

    deploy.ps1 calls it with -LibsOnly, which is the part that is safe on every deploy: nothing is resolved while the poms are as they were at the last
    sync (a stamp in the install), and a server that is running keeps its jars unless -StopRunning says it may be stopped.

.PARAMETER From
    The commit whose files the install holds: for a merge, its first parent; for a tester, the patch tag they last deployed. Defaults to the first
    parent of -To. Not needed with -LibsOnly.

.PARAMETER To
    The merged commit. Defaults to HEAD.

.PARAMETER ServerRoot
    The install. Defaults to AION_SERVER_HOME.

.PARAMETER LibsOnly
    Only the libraries and the own jars, no data, configuration or SQL.

.PARAMETER StopRunning
    With -Apply, the login and chat servers may be stopped to change their jars. The game server never is: stop-server.ps1 saves the players.

.PARAMETER Apply
    Do it.
#>
param(
    [string]$From,
    [string]$To = 'HEAD',
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$LibsOnly,
    [switch]$StopRunning,
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }
if (-not $LibsOnly) {
    if (-not $From) { $From = (git -C $repo rev-parse "$To^1").Trim() }
    Write-Output "Install $ServerRoot holds the files of $(if ($From -match '^[0-9a-f]{12,}$') { $From.Substring(0, 9) } else { $From }); target $To."
}

# a file of a revision, as the bytes git holds, read back as one character a byte so that two can be compared whatever their encoding
function Get-Text([string]$rev, [string]$path) {
    $tmp = [IO.Path]::GetTempFileName()
    try {
        cmd /c "git -C `"$repo`" show ${rev}:${path} > `"$tmp`" 2>nul"
        if ($LASTEXITCODE -ne 0) { return $null }
        return [Text.Encoding]::GetEncoding(28591).GetString([IO.File]::ReadAllBytes($tmp)).Replace("`r`n", "`n")
    } finally { Remove-Item $tmp -ErrorAction SilentlyContinue }
}

function Get-InstalledText([string]$path) {
    [Text.Encoding]::GetEncoding(28591).GetString([IO.File]::ReadAllBytes($path)).Replace("`r`n", "`n")
}

function Install-File([string]$path, [string]$target) {
    New-Item -ItemType Directory -Force (Split-Path -Parent $target) | Out-Null
    # to a file of its own first: a git that fails after the redirect has opened the target would leave it empty
    $tmp = [IO.Path]::GetTempFileName()
    try {
        cmd /c "git -C `"$repo`" show ${To}:${path} > `"$tmp`""
        if ($LASTEXITCODE -ne 0) { throw "Could not read $path at $To" }
        Copy-Item $tmp $target -Force
    } finally { Remove-Item $tmp -ErrorAction SilentlyContinue }
}

$mainClass = @{ 'game-server' = 'com.aionemu.gameserver.GameServer'; 'login-server' = 'com.aionemu.loginserver.LoginServer'; 'chat-server' = 'com.aionemu.chatserver.ChatServer' }

# whether the jars of a server may be changed now: not while its JVM holds them, unless it may be stopped (never the game server, whose shutdown saves the players)
function Test-MayChange([string]$module, [string]$libs) {
    if (-not $Apply) { return $true }
    # held or not is asked of the jars themselves: a process of the same name may belong to another install
    $held = $false
    foreach ($jar in Get-ChildItem $libs -Filter '*.jar') {
        try { [IO.File]::Open($jar.FullName, 'Open', 'ReadWrite', 'None').Close() } catch { $held = $true; break }
    }
    if (-not $held) { return $true }
    $process = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like "*$($mainClass[$module])*" } | Select-Object -First 1
    if (-not $process) { return $true }
    if ($StopRunning -and $module -ne 'game-server') {
        Stop-Process -Id $process.ProcessId -Confirm:$false
        Wait-Process -Id $process.ProcessId -Timeout 30 -ErrorAction SilentlyContinue
        Write-Output "Stopped the $module (PID $($process.ProcessId)) to change its jars."
        return $true
    }
    Write-Warning "The $module is running and holds its jars: not changed. Stop it (stop-server.ps1 for the game server) and run this again."
    return $false
}

# ---- data and configuration -------------------------------------------------------------------------------------------------------------------
if (-not $LibsOnly) {
    $areas = 'game-server/data/static_data', 'game-server/config', 'login-server/config', 'chat-server/config'
    $replaced = 0; $same = 0; $left = @()
    foreach ($line in git -C $repo diff -M --name-status $From $To -- $areas) {
        $parts = $line -split "`t"
        $status = $parts[0].Substring(0, 1)
        # a rename is a removal and an addition
        $changes = [Collections.Generic.List[object]]::new()
        if ($status -eq 'R') {
            $changes.Add(@('D', $parts[1]))
            $changes.Add(@('A', $parts[2]))
        } else {
            $changes.Add(@($status, $parts[1]))
        }
        foreach ($change in $changes) {
            $kind, $path = $change
            $target = Join-Path $ServerRoot $path.Replace('/', '\')
            $exists = Test-Path $target
            $was = Get-Text $From $path
            if ($kind -eq 'D') {
                if (-not $exists) { $same++; continue }
                if ((Get-InstalledText $target) -ceq $was) {
                    Write-Output "REMOVE  $path"
                    if ($Apply) { Remove-Item $target }
                    $replaced++
                } else { $left += "$path (removed upstream, edited here)" }
            } else {
                $now = Get-Text $To $path
                if ($exists -and (Get-InstalledText $target) -ceq $now) { $same++; continue } # already the wanted file, from an earlier run or by hand
                if ($exists -and $kind -eq 'M' -and (Get-InstalledText $target) -cne $was) { $left += "$path (changed upstream, edited here)"; continue }
                if ($exists -and $kind -eq 'A') { $left += "$path (added upstream, a different file is there)"; continue }
                if (-not $exists -and $kind -eq 'M') { Write-Output "ADD     $path (not in the install)" } else { Write-Output "REPLACE $path" }
                if ($Apply) { Install-File $path $target }
                $replaced++
            }
        }
    }
    Write-Output "Data and configuration: $replaced to change, $same already as wanted, $($left.Count) edited here."
    foreach ($file in $left) { Write-Output "LEFT    $file" }
}

# ---- libraries --------------------------------------------------------------------------------------------------------------------------------
$own = '^(commons|game-server|login-server|chat-server)-.*\.jar$'
$modules = 'game-server', 'login-server', 'chat-server'

# what the libraries depend on is the poms alone: when they are as they were at the last sync there is nothing to resolve
$pomFiles = @('pom.xml', 'commons\pom.xml') + ($modules | ForEach-Object { "$_\pom.xml" })
$pomHash = [BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash(
    [Text.Encoding]::UTF8.GetBytes((($pomFiles | ForEach-Object { [IO.File]::ReadAllText((Join-Path $repo $_)).Replace("`r`n", "`n") }) -join "`0")))).Replace('-', '')
# and which servers are installed: one installed later has not had its libraries
$present = ($modules | Where-Object { Test-Path (Join-Path $ServerRoot "$_\libs") }) -join ','
$stampValue = "$pomHash $present"
$stampFile = Join-Path $ServerRoot '.libs-stamp'
$resolve = -not ($LibsOnly -and (Test-Path $stampFile) -and ((Get-Content $stampFile -Raw).Trim() -eq $stampValue))
if (-not $resolve) { Write-Output 'Libraries: the poms are as at the last sync.' }

# the modules' own jars the others load: every server reads commons from its own folder, and the login server's jar is nobody's but this script's.
# deploy.ps1 carries the game server's jars and the chat server's. Built by the caller (mvn package); a jar not built is not touched.
function Find-Jar([string]$module, [string]$prefix) {
    Get-ChildItem (Join-Path $repo "$module\target") -Filter "$prefix-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Sort-Object LastWriteTime | Select-Object -Last 1
}
$commons = Find-Jar 'commons' 'commons'
$ownJars = @{
    'game-server'  = @()
    'login-server' = @($commons, (Find-Jar 'login-server' 'login-server'))
    'chat-server'  = @($commons)
}

$everythingDone = $true
foreach ($module in $modules) {
    $libs = Join-Path $ServerRoot "$module\libs"
    if (-not (Test-Path $libs)) { continue }
    $add = @(); $drop = @()
    if ($resolve) {
        $wanted = Join-Path $env:TEMP "aion-deps-$module"
        Remove-Item $wanted -Recurse -Force -ErrorAction SilentlyContinue
        # the compile phase, with nothing compiled, is what lets the reactor find commons on a clone that has never been built (without a phase Maven asks
        # the local repository for it and fails); the project's own jars are left out of the copy, since $own drops them below anyway
        $listing = mvn -q -f (Join-Path $repo 'pom.xml') "-Dmaven.main.skip=true" "-Dmaven.test.skip=true" compile dependency:copy-dependencies -pl $module -am `
            "-DoutputDirectory=$wanted" -DincludeScope=runtime "-DexcludeGroupIds=com.aionemu" 2>&1
        if ($LASTEXITCODE -ne 0) {
            $listing | Select-Object -Last 25 | ForEach-Object { Write-Output "  mvn: $_" }
            throw "Could not list the libraries of $module"
        }
        $need = @(Get-ChildItem $wanted -Filter '*.jar' | Where-Object { $_.Name -notmatch $own -and $_.Name -notmatch '-linux-' })
        $have = @(Get-ChildItem $libs -Filter '*.jar' | Where-Object { $_.Name -notmatch $own })
        $add = @($need | Where-Object { $have.Name -notcontains $_.Name })
        $drop = @($have | Where-Object { $need.Name -notcontains $_.Name })
    }
    $differ = @($ownJars[$module] | Where-Object {
        $_ -and -not ((Test-Path (Join-Path $libs $_.Name)) -and (Get-FileHash (Join-Path $libs $_.Name)).Hash -eq (Get-FileHash $_.FullName).Hash)
    })
    # an own jar of another name (a new version) leaves the old one behind, and two versions of a class on the classpath is not a choice anyone made
    $stale = @()
    foreach ($jar in $ownJars[$module]) {
        if (-not $jar) { continue }
        $prefix = $jar.Name -replace '-\d.*$', ''
        $stale += @(Get-ChildItem $libs -Filter '*.jar' | Where-Object { $_.Name -match "^$([regex]::Escape($prefix))-\d" -and $_.Name -ne $jar.Name -and $_.Name -notmatch '-(sources|javadoc)\.jar$' })
    }
    $drop = @($drop) + @($stale)
    if ($resolve -or $differ.Count -gt 0 -or $stale.Count -gt 0) {
        Write-Output "Libraries of ${module}: $($add.Count) to add, $($drop.Count) to remove, $($differ.Count) own jar(s) to copy."
    }
    foreach ($jar in $add) { Write-Output "ADD     $module/libs/$($jar.Name)" }
    foreach ($jar in $drop) { Write-Output "REMOVE  $module/libs/$($jar.Name)" }
    foreach ($jar in $differ) { Write-Output "OWN     $module/libs/$($jar.Name)" }
    if ($Apply -and ($add.Count + $drop.Count + $differ.Count) -gt 0) {
        if (Test-MayChange $module $libs) {
            foreach ($jar in $add) { Copy-Item $jar.FullName $libs }
            foreach ($jar in $drop) { Remove-Item $jar.FullName }
            foreach ($jar in $differ) { Copy-Item $jar.FullName $libs -Force }
        } else { $everythingDone = $false }
    }
}
if ($Apply -and $resolve -and $everythingDone) { Set-Content $stampFile $stampValue -Encoding ascii }

# ---- SQL --------------------------------------------------------------------------------------------------------------------------------------
# the statements of update.sql the database has not had yet, once each, after a backup (apply-sql.ps1; deploy.ps1 runs it too)
if (-not $LibsOnly) {
    & (Join-Path $PSScriptRoot 'apply-sql.ps1') -ServerRoot $ServerRoot -Apply:$Apply
}

if (-not $Apply) { Write-Output 'Nothing changed: -Apply does it.' }
