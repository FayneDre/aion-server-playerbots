<#
.SYNOPSIS
    Tries the install's Java runtime end to end: the pinned JDK is checked and trimmed (package-java.ps1), then the game server's handlers are compiled with the
    runtime's own compiler against an install's libraries, as the game server does at start.

.DESCRIPTION
    Nothing of an install is changed. The runtime is built in the temp directory and removed afterwards (-Keep leaves it). Checks, each one PASS or FAIL:

      - the zip is the pinned one (SHA-256) and is Microsoft's (signature);
      - package-java.ps1 makes the runtime, and its test program runs on it (the compiler answers, TLS, CP850, the HTTP server);
      - the runtime is under 120 MB unpacked and its zip under 80 MB (it was 75 and 55 when measured);
      - every handler source of the install compiles with the runtime's javac against the install's libs, with no error.

    What this does not try: starting the servers on it. That was done on 13 November (the whole stack, 1028 bots, no error line); to repeat it,
    put the folder at <install>\runtime and run start-server.ps1, which then runs the servers on it.

.PARAMETER Zip
    The official JDK zip the pin file names (microsoft-jdk-<version>-windows-x64.zip).

.PARAMETER ServerRoot
    An install with libs and handlers (game-server\libs, game-server\data\handlers). Defaults to AION_SERVER_HOME.

.PARAMETER Keep
    Leave the built runtime and its zip in the temp directory.

.EXAMPLE
    .\tools\check-portable-java.ps1 -Zip C:\dl\microsoft-jdk-25.0.4.1-windows-x64.zip
#>
param(
    [Parameter(Mandatory = $true)][string]$Zip,
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [switch]$Keep
)

$ErrorActionPreference = 'Stop'
if (-not $ServerRoot) { throw 'Set AION_SERVER_HOME or pass -ServerRoot.' }
$libs = Join-Path $ServerRoot 'game-server\libs'
$handlers = Join-Path $ServerRoot 'game-server\data\handlers'
foreach ($path in $libs, $handlers) { if (-not (Test-Path $path)) { throw "$path does not exist: pass an install that holds the game server's libraries and handlers." } }

$failures = 0
function Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    if ($Ok) { Write-Host "PASS  $Name" -ForegroundColor Green } else { Write-Host "FAIL  $Name  $Detail" -ForegroundColor Red; $script:failures++ }
}

$work = Join-Path $env:TEMP "aion-check-java-$PID"
$runtime = Join-Path $work 'runtime'
$zipOut = Join-Path $work 'runtime.zip'
New-Item -ItemType Directory $work | Out-Null
try {
    $pin = Get-Content (Join-Path $PSScriptRoot 'java\portable-java.json') -Raw | ConvertFrom-Json
    $hash = (Get-FileHash $Zip -Algorithm SHA256).Hash.ToLower()
    Check 'the zip is the pinned one' ($hash -eq $pin.sha256) "SHA-256 $hash, pinned $($pin.sha256)"

    $made = $true
    try { & (Join-Path $PSScriptRoot 'package-java.ps1') -Source $Zip -OutDir $runtime -ZipOut $zipOut | Out-Null } catch { $made = $false; Write-Host "      $($_.Exception.Message)" -ForegroundColor Red }
    Check 'package-java.ps1 makes the runtime (signature, jlink, the test program)' $made
    if ($made) {
        $size = (Get-ChildItem $runtime -Recurse -File | Measure-Object Length -Sum).Sum
        Check ("the runtime is under 120 MB unpacked ({0:N0} MB)" -f ($size / 1MB)) ($size -lt 120MB)
        Check ("its zip is under 80 MB ({0:N0} MB)" -f ((Get-Item $zipOut).Length / 1MB)) ((Get-Item $zipOut).Length -lt 80MB)
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [IO.Compression.ZipFile]::OpenRead($zipOut)
        try { Check 'the zip has no backslash in an entry name' (-not ($archive.Entries | Where-Object { $_.FullName.Contains('\') })) } finally { $archive.Dispose() }

        $sources = @(Get-ChildItem $handlers -Recurse -Filter *.java | ForEach-Object FullName)
        $list = Join-Path $work 'sources.txt'
        [IO.File]::WriteAllLines($list, ($sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' }), (New-Object Text.UTF8Encoding($false)))
        $cp = (Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName -replace '\\', '/' }) -join ';'
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $out = & (Join-Path $runtime 'bin\javac.exe') -proc:none -nowarn -encoding UTF-8 -cp $cp -d (Join-Path $work 'classes') "@$list" 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
        $ErrorActionPreference = $previous
        $classes = @(Get-ChildItem (Join-Path $work 'classes') -Recurse -Filter *.class -ErrorAction SilentlyContinue).Count
        $detail = ($out | Select-Object -First 3) -join ' | '
        Check "the $($sources.Count) handler sources compile with the runtime's javac ($classes classes)" ($code -eq 0 -and $sources.Count -gt 0) $detail
    }
} finally {
    if ($Keep) { Write-Host "Kept: $work" } else { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
}

if ($failures -gt 0) { Write-Host "$failures check(s) failed." -ForegroundColor Red; exit 1 }
Write-Host 'All checks passed.' -ForegroundColor Green
