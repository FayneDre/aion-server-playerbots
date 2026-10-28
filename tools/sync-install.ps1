<#
.SYNOPSIS
    Brings the installed servers in line with a merge of the upstream: the libraries, the data and configuration files upstream changed, and the
    SQL it added. Shows what it would do; changes nothing without -Apply.

.DESCRIPTION
    deploy.ps1 copies the jars of the modules and the handlers, and nothing else, so a merge that moves to new libraries (Netty 4.2 for jakarta XML
    binding, newer Hikari, MySQL connector, logback), changes the static data or the configuration, or adds columns to the database leaves an
    install that cannot start. The install is a copy made once, from the repository of that day, so what changed since is a three way question:

    - a file the install still holds as the repository had it (-From) is replaced by the new one (-To), or removed if upstream removed it;
    - a file the install holds otherwise, edited here, is left alone and named, for a hand merge;
    - a library the new build needs and the install lacks is added; one the build no longer needs is removed (the servers' own jars are deploy.ps1's);
    - the SQL upstream added to game-server/sql/update.sql is written to a file and the command to run it printed, never run: it renames columns the
      running server reads, so it goes between stopping the old server and starting the new one, after a backup.

.PARAMETER From
    The commit whose files the install holds. For a merge, its first parent. Defaults to the first parent of -To.

.PARAMETER To
    The merged commit. Defaults to HEAD.

.PARAMETER ServerRoot
    The install. Defaults to AION_SERVER_HOME.

.PARAMETER Apply
    Do it.
#>
param(
    [string]$From,
    [string]$To = 'HEAD',
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [string]$SqlOut = (Join-Path $env:TEMP 'aion-upstream-update.sql'),
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }
if (-not $From) { $From = (git -C $repo rev-parse "$To^1").Trim() }
Write-Output "Install $ServerRoot holds the files of $($From.Substring(0, 9)); target $To."

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
    cmd /c "git -C `"$repo`" show ${To}:${path} > `"$target`""
    if ($LASTEXITCODE -ne 0) { throw "Could not write $target" }
}

# ---- data and configuration -------------------------------------------------------------------------------------------------------------------
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
            if ($exists -and $kind -eq 'M' -and (Get-InstalledText $target) -cne $was) { $left += "$path (changed upstream, edited here)"; continue }
            if ($exists -and $kind -eq 'A' -and (Get-InstalledText $target) -cne (Get-Text $To $path)) { $left += "$path (added upstream, a different file is there)"; continue }
            if (-not $exists -and $kind -eq 'M') { Write-Output "ADD     $path (not in the install)" } else { Write-Output "REPLACE $path" }
            if ($Apply) { Install-File $path $target }
            $replaced++
        }
    }
}
Write-Output "Data and configuration: $replaced to change, $same already as wanted, $($left.Count) edited here."
foreach ($file in $left) { Write-Output "LEFT    $file" }

# ---- libraries --------------------------------------------------------------------------------------------------------------------------------
$own = '^(commons|game-server|login-server|chat-server)-.*\.jar$'
foreach ($module in 'game-server', 'login-server', 'chat-server') {
    $wanted = Join-Path $env:TEMP "aion-deps-$module"
    Remove-Item $wanted -Recurse -Force -ErrorAction SilentlyContinue
    mvn -q -f (Join-Path $repo 'pom.xml') dependency:copy-dependencies -pl $module -am "-DoutputDirectory=$wanted" -DincludeScope=runtime | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Could not list the libraries of $module" }
    $libs = Join-Path $ServerRoot "$module\libs"
    $need = @(Get-ChildItem $wanted -Filter '*.jar' | Where-Object { $_.Name -notmatch $own -and $_.Name -notmatch '-linux-' })
    $have = @(Get-ChildItem $libs -Filter '*.jar' | Where-Object { $_.Name -notmatch $own })
    $add = @($need | Where-Object { $have.Name -notcontains $_.Name })
    $drop = @($have | Where-Object { $need.Name -notcontains $_.Name })
    Write-Output "Libraries of ${module}: $($add.Count) to add, $($drop.Count) to remove."
    foreach ($jar in $add) { Write-Output "ADD     $module/libs/$($jar.Name)"; if ($Apply) { Copy-Item $jar.FullName $libs } }
    foreach ($jar in $drop) { Write-Output "REMOVE  $module/libs/$($jar.Name)"; if ($Apply) { Remove-Item $jar.FullName } }
}

# the modules' own jars: deploy.ps1 carries the game server's and commons into the game server, and the chat server's; but every server loads commons
# from its own folder, and the login server's jar is nobody's. Left as they were, the chat server met a commons class that did not exist yet (28 October).
# Built by the caller (mvn package); a jar not built is not touched.
$commons = Get-ChildItem (Join-Path $repo 'commons\target') -Filter 'commons-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -First 1
$login = Get-ChildItem (Join-Path $repo 'login-server\target') -Filter 'login-server-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Select-Object -First 1
foreach ($own in @(@($commons, 'login-server'), @($commons, 'chat-server'), @($login, 'login-server'))) {
    $jar, $module = $own
    if (-not $jar) { continue }
    $installed = Join-Path $ServerRoot "$module\libs\$($jar.Name)"
    if ((Test-Path $installed) -and (Get-FileHash $installed).Hash -eq (Get-FileHash $jar.FullName).Hash) { continue }
    Write-Output "OWN     $module/libs/$($jar.Name)"
    if ($Apply) { Copy-Item $jar.FullName (Split-Path -Parent $installed) -Force }
}

# ---- SQL --------------------------------------------------------------------------------------------------------------------------------------
# the merged update.sql is upstream's statements and then the bot tables (which SchemaUpdater creates by itself); the first are the ones to run
git -C $repo diff --quiet $From $To -- game-server/sql/update.sql
if ($LASTEXITCODE -ne 0) {
    $sql = (Get-Text $To 'game-server/sql/update.sql').Split("`n")
    $end = 0
    while ($end -lt $sql.Count -and $sql[$end] -notmatch 'playerbot_') { $end++ }
    [IO.File]::WriteAllText($SqlOut, (($sql[0..($end - 1)] -join "`n").TrimEnd() + "`n"), [Text.Encoding]::GetEncoding(28591))
    Write-Output "SQL upstream added, in $SqlOut. Between stopping the old server and starting the new one, after a backup of aion_gs:"
    Write-Output "  mysql -u <user> -p aion_gs < $SqlOut"
}

if (-not $Apply) { Write-Output 'Nothing changed: -Apply does it.' }
