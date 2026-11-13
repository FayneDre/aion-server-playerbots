<#
.SYNOPSIS
    Trims the official MySQL Windows zip to what the servers run (about 1.3 GB unpacked down to a fraction), and checks it is Oracle's before it does.

.DESCRIPTION
    The zip holds 24 programs, debug symbols (809 MB), a debug copy of every plugin, Japanese full-text dictionaries, 25 languages of messages and the
    headers and libraries to build against. A server needs mysqld and three clients. This keeps:

      - bin: mysqld.exe, mysql.exe, mysqladmin.exe, mysqldump.exe, and the DLLs they import, found by reading the import names out of each program
        and out of each DLL in turn (nothing is kept on a guess, nothing needed is dropped);
      - lib\private (ICU data, which mysqld reads);
      - lib\plugin only with -WithPlugins (the servers use none);
      - share: the character sets, the English messages and the two message lists;
      - LICENSE and README, and a NOTICE that says what this is (GPL version 2: the programs are Oracle's, unmodified, only files are left out).

    Nothing is changed in a file and nothing is written outside -OutDir (the zip is unpacked into a new folder of the temp directory, removed
    afterwards). The programs must carry a valid signature from Oracle, or nothing is made. The result is described in runtime.json (version, the SHA-256 of
    the zip it came from, size) so a manifest can pin it.

    Run setup-mysql.ps1 on the result: it makes a database from it. Never run this against a MySQL that holds data: it only reads the source.

.PARAMETER Source
    The official zip (mysql-<version>-winx64.zip), or the folder it unpacks to.

.PARAMETER OutDir
    The folder to make. It must not exist.

.PARAMETER ZipOut
    Also pack OutDir into this zip.

.PARAMETER WithPlugins
    Keep lib\plugin (45 MB) too, for a configuration that loads plugins or components.

.EXAMPLE
    .\tools\package-mysql.ps1 -Source C:\dl\mysql-26.7.0-winx64.zip -OutDir C:\build\mysql -ZipOut C:\build\mysql-26.7.0-trimmed.zip
#>
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$ZipOut,
    [switch]$WithPlugins
)

$ErrorActionPreference = 'Stop'
if (Test-Path $OutDir) { throw "$OutDir exists already: this makes a folder from nothing and never writes into one." }

$unpacked = $null
$zipHash = $null
try {
    if ((Test-Path $Source -PathType Leaf) -and $Source -like '*.zip') {
        $zipHash = (Get-FileHash $Source -Algorithm SHA256).Hash.ToLower()
        $unpacked = Join-Path $env:TEMP ("aion-mysql-unpack-$PID")
        New-Item -ItemType Directory $unpacked | Out-Null
        Write-Host "Unpacking $Source..." -ForegroundColor Cyan
        Expand-Archive $Source -DestinationPath $unpacked
        $src = (Get-ChildItem $unpacked -Directory | Select-Object -First 1).FullName
    } elseif (Test-Path $Source -PathType Container) {
        $src = (Resolve-Path $Source).Path
    } else {
        throw "$Source is neither a zip nor a folder."
    }
    if (-not (Test-Path (Join-Path $src 'bin\mysqld.exe'))) { throw "No bin\mysqld.exe under $src." }

    # ---- Oracle's, or nothing ------------------------------------------------------------------------------------------------------------
    foreach ($name in 'mysqld.exe', 'mysql.exe', 'mysqladmin.exe', 'mysqldump.exe') {
        $signature = Get-AuthenticodeSignature (Join-Path $src "bin\$name")
        if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O="?Oracle') {
            throw "$name is not signed by Oracle (status $($signature.Status)): nothing is made."
        }
    }
    Write-Host 'Signatures: Oracle, valid.' -ForegroundColor Green

    # ---- the DLLs the programs import, transitively --------------------------------------------------------------------------------------
    $binDir = Join-Path $src 'bin'
    $available = @{}
    Get-ChildItem $binDir -Filter *.dll | ForEach-Object { $available[$_.Name.ToLower()] = $_.Name }
    function Get-ImportedNames([string]$path) {
        # ISO-8859-1 by number: Encoding.Latin1 does not exist in Windows PowerShell 5.1
        $text = [Text.Encoding]::GetEncoding(28591).GetString([IO.File]::ReadAllBytes($path))
        [regex]::Matches($text, '[A-Za-z0-9_\-\.]{1,80}\.dll', 'IgnoreCase') | ForEach-Object { $_.Value.ToLower() } | Sort-Object -Unique
    }
    $programs = 'mysqld.exe', 'mysql.exe', 'mysqladmin.exe', 'mysqldump.exe'
    $keepDll = @{}
    $queue = New-Object Collections.Generic.Queue[string]
    foreach ($p in $programs) { $queue.Enqueue((Join-Path $binDir $p)) }
    while ($queue.Count -gt 0) {
        foreach ($name in Get-ImportedNames $queue.Dequeue()) {
            if ($available.ContainsKey($name) -and -not $keepDll.ContainsKey($name)) {
                $keepDll[$name] = $available[$name]
                $queue.Enqueue((Join-Path $binDir $available[$name]))
            }
        }
    }

    # ---- the copy ------------------------------------------------------------------------------------------------------------------------
    New-Item -ItemType Directory $OutDir | Out-Null
    New-Item -ItemType Directory (Join-Path $OutDir 'bin') | Out-Null
    foreach ($p in $programs) { Copy-Item (Join-Path $binDir $p) (Join-Path $OutDir "bin\$p") }
    foreach ($dll in $keepDll.Values) { Copy-Item (Join-Path $binDir $dll) (Join-Path $OutDir "bin\$dll") }
    if (Test-Path (Join-Path $src 'lib\private')) {
        New-Item -ItemType Directory (Join-Path $OutDir 'lib') | Out-Null
        Copy-Item (Join-Path $src 'lib\private') (Join-Path $OutDir 'lib\private') -Recurse
    }
    if ($WithPlugins -and (Test-Path (Join-Path $src 'lib\plugin'))) {
        New-Item -ItemType Directory -Force (Join-Path $OutDir 'lib\plugin') | Out-Null
        Get-ChildItem (Join-Path $src 'lib\plugin') -File | Copy-Item -Destination (Join-Path $OutDir 'lib\plugin')
    }
    $shareOut = Join-Path $OutDir 'share'
    New-Item -ItemType Directory $shareOut | Out-Null
    foreach ($dir in 'charsets', 'english') { Copy-Item (Join-Path $src "share\$dir") (Join-Path $shareOut $dir) -Recurse }
    Get-ChildItem (Join-Path $src 'share') -File -Filter '*.txt' | Copy-Item -Destination $shareOut
    foreach ($file in 'LICENSE', 'README') { if (Test-Path (Join-Path $src $file)) { Copy-Item (Join-Path $src $file) $OutDir } }

    # ---- what it is ----------------------------------------------------------------------------------------------------------------------
    $versionText = & (Join-Path $OutDir 'bin\mysqld.exe') --version 2>&1 | ForEach-Object { "$_" }
    $version = if (($versionText -join ' ') -match 'Ver (\S+)') { $Matches[1] } else { 'unknown' }
    Set-Content (Join-Path $OutDir 'NOTICE.txt') -Encoding UTF8 -Value @(
        "MySQL Community Server $version, Windows x64.",
        '',
        'These programs are Oracle''s, unmodified: this folder is the official zip with files left out (debug symbols, programs the servers do not run,',
        'plugins, extra languages). They are licensed under the GNU General Public License, version 2 (the file LICENSE beside this one).',
        'The source code of this version is at https://github.com/mysql/mysql-server and https://dev.mysql.com/downloads/mysql/.',
        'MySQL is a trademark of Oracle and its affiliates.')
    $size = (Get-ChildItem $OutDir -Recurse -File | Measure-Object Length -Sum).Sum
    $info = [ordered]@{
        name = 'mysql'; version = $version; sourceZipSha256 = $zipHash; trimmed = $true; withPlugins = [bool]$WithPlugins
        programs = $programs; dlls = @($keepDll.Values | Sort-Object); unpackedBytes = $size
    }
    $info | ConvertTo-Json | Set-Content (Join-Path $OutDir 'runtime.json') -Encoding UTF8
    Write-Host ("Made {0}: MySQL {1}, {2:N0} MB unpacked, {3} DLL(s) kept of {4}." -f $OutDir, $version, ($size / 1MB), $keepDll.Count, $available.Count) -ForegroundColor Green

    if ($ZipOut) {
        if (Test-Path $ZipOut) { throw "$ZipOut exists already." }
        # Built entry by entry with forward slashes, which every zip reader expects: Compress-Archive of Windows PowerShell 5.1 writes backslashes
        # (82 of 86 entries on 13 November), which Windows accepts and a Node extractor may refuse or turn into files named "bin\mysqld.exe"
        Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
        $root = (Resolve-Path $OutDir).Path.TrimEnd('\')
        $archive = [IO.Compression.ZipFile]::Open($ZipOut, 'Create')
        try {
            foreach ($file in Get-ChildItem $root -Recurse -File) {
                $entryName = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
                [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $entryName, [IO.Compression.CompressionLevel]::Optimal)
            }
        } finally {
            $archive.Dispose()
        }
        Write-Host ("Zip: {0} ({1:N0} MB, SHA-256 {2})" -f $ZipOut, ((Get-Item $ZipOut).Length / 1MB), (Get-FileHash $ZipOut -Algorithm SHA256).Hash.ToLower())
    }
} finally {
    if ($unpacked -and (Test-Path $unpacked)) { Remove-Item $unpacked -Recurse -Force -ErrorAction SilentlyContinue }
}
