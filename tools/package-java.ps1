<#
.SYNOPSIS
    Makes the Java runtime an install carries (<install>\runtime) from the official Microsoft JDK, with jlink, and checks the JDK is Microsoft's first.

.DESCRIPTION
    A full JDK is 375 MB unpacked. The servers run on a part of it, so jlink builds a runtime of the modules they need (the list is in
    tools/java/portable-java.json: what jdeps finds in the libraries of the three servers, plus what it cannot see: jdk.compiler, which the game server
    calls by reflection to compile its handlers at start, the TLS curves MySQL's connection uses, and the character sets of the console). 75 MB
    unpacked, 55 MB zipped, 28 modules.

    Nothing is changed in a file of the JDK. The programs must carry a valid signature from the pinned signer, and a zip must be the pinned one
    (SHA-256 in the pin file), or nothing is made. The result is described in runtime.json (version, SHA-256 of the zip it came from, modules, size) so a
    manifest can pin it, and a small program is run on it at the end (the compiler answers, TLS, the console's character set): -SkipSmoke leaves it out.

.PARAMETER Source
    The official zip (microsoft-jdk-<version>-windows-x64.zip), or the folder it unpacks to.

.PARAMETER OutDir
    The folder to make. It must not exist.

.PARAMETER ZipOut
    Also pack OutDir into this zip.

.PARAMETER PinFile
    The pin file. Defaults to tools/java/portable-java.json.

.EXAMPLE
    .\tools\package-java.ps1 -Source C:\dl\microsoft-jdk-25.0.4.1-windows-x64.zip -OutDir C:\build\runtime -ZipOut C:\build\java-25.0.4.1-runtime.zip
#>
param(
    [Parameter(Mandatory = $true)][string]$Source,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$ZipOut,
    [string]$PinFile = (Join-Path $PSScriptRoot 'java\portable-java.json'),
    [switch]$SkipSmoke
)

$ErrorActionPreference = 'Stop'
if (Test-Path $OutDir) { throw "$OutDir exists already: this makes a folder from nothing and never writes into one." }
$pin = Get-Content $PinFile -Raw | ConvertFrom-Json

$unpacked = $null
$zipHash = $null
try {
    if ((Test-Path $Source -PathType Leaf) -and $Source -like '*.zip') {
        $zipHash = (Get-FileHash $Source -Algorithm SHA256).Hash.ToLower()
        if ($zipHash -ne $pin.sha256) { throw "$Source is not the pinned zip (SHA-256 $zipHash, pinned $($pin.sha256)): nothing is made." }
        $unpacked = Join-Path $env:TEMP ("aion-java-unpack-$PID")
        New-Item -ItemType Directory $unpacked | Out-Null
        Write-Host "Unpacking $Source..." -ForegroundColor Cyan
        Expand-Archive $Source -DestinationPath $unpacked
        $src = (Get-ChildItem $unpacked -Directory | Select-Object -First 1).FullName
    } elseif (Test-Path $Source -PathType Container) {
        $src = (Resolve-Path $Source).Path
    } else {
        throw "$Source is neither a zip nor a folder."
    }
    foreach ($tool in 'java.exe', 'jlink.exe') { if (-not (Test-Path (Join-Path $src "bin\$tool"))) { throw "No bin\${tool} under ${src}: a full JDK is needed to build a runtime." } }

    # ---- the pinned signer's, or nothing -------------------------------------------------------------------------------------------------
    foreach ($name in 'java.exe', 'jlink.exe', 'javac.exe') {
        $signature = Get-AuthenticodeSignature (Join-Path $src "bin\$name")
        if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch [regex]::Escape($pin.signer)) {
            throw "$name is not signed by $($pin.signer) (status $($signature.Status)): nothing is made."
        }
    }
    Write-Host "Signatures: $($pin.signer), valid." -ForegroundColor Green

    # ---- jlink ---------------------------------------------------------------------------------------------------------------------------
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # jlink writes to stderr, which 5.1 turns into an error under 'Stop'
    $log = & (Join-Path $src 'bin\jlink.exe') --add-modules ($pin.modules -join ',') --output $OutDir --strip-debug --no-header-files --no-man-pages --compress zip-6 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous
    if ($code -ne 0 -or -not (Test-Path (Join-Path $OutDir 'bin\java.exe'))) { throw "jlink failed (exit $code): $($log -join ' ')" }

    # ---- what it is ----------------------------------------------------------------------------------------------------------------------
    $java = Join-Path $OutDir 'bin\java.exe'
    $ErrorActionPreference = 'Continue'
    $versionText = & $java -version 2>&1 | ForEach-Object { "$_" }
    $moduleCount = (& $java --list-modules | Measure-Object).Count
    $ErrorActionPreference = $previous
    $version = if (($versionText -join ' ') -match 'openjdk version "([^"]+)"') { $Matches[1] } else { 'unknown' }
    if ($version -ne $pin.version) { throw "The runtime is Java $version, the pin says $($pin.version): nothing is kept." }
    Set-Content (Join-Path $OutDir 'NOTICE.txt') -Encoding UTF8 -Value @(
        "$($pin.vendor) $version, Windows x64: a runtime of $moduleCount modules made with jlink.",
        '',
        'The modules are Microsoft''s, unmodified: this folder holds the part of the official JDK that the servers use, linked together (jlink) with the',
        'debug information and the manuals left out. They are licensed under the GNU General Public License, version 2, with the Classpath Exception',
        '(the folder legal holds the licence of every module). The source code is at https://github.com/microsoft/openjdk-jdk25u and',
        'https://www.microsoft.com/openjdk. OpenJDK is a trademark of Oracle and its affiliates.')
    $size = (Get-ChildItem $OutDir -Recurse -File | Measure-Object Length -Sum).Sum
    $info = [ordered]@{
        name = 'java'; version = $version; vendor = $pin.vendor; sourceZipSha256 = $zipHash; modules = @($pin.modules); moduleCount = $moduleCount; unpackedBytes = $size
    }
    $info | ConvertTo-Json | Set-Content (Join-Path $OutDir 'runtime.json') -Encoding UTF8
    Write-Host ("Made {0}: Java {1}, {2} modules, {3:N0} MB unpacked." -f $OutDir, $version, $moduleCount, ($size / 1MB)) -ForegroundColor Green

    # ---- a program run on it: what the servers ask of Java that jdeps cannot see ----------------------------------------------------------
    if (-not $SkipSmoke) {
        $smoke = Join-Path $env:TEMP "aion-java-smoke-$PID"
        New-Item -ItemType Directory $smoke | Out-Null
        try {
            [IO.File]::WriteAllText((Join-Path $smoke 'Smoke.java'), @'
public class Smoke {
    public static void main(String[] args) throws Exception {
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("no compiler: the game server could not compile its handlers");
        if (!java.nio.charset.Charset.isSupported("CP850")) throw new IllegalStateException("no CP850: -DconsoleEncoding=CP850 would fail");
        var tls = javax.net.ssl.SSLContext.getDefault().getSupportedSSLParameters().getProtocols();
        if (tls.length == 0) throw new IllegalStateException("no TLS protocol");
        var http = com.sun.net.httpserver.HttpServer.create();
        http.stop(0);
        System.out.println("compiler, CP850, TLS (" + tls.length + " protocols) and the HTTP server: ok");
    }
}
'@, (New-Object Text.UTF8Encoding($false)))
            $ErrorActionPreference = 'Continue'
            $out = & $java (Join-Path $smoke 'Smoke.java') 2>&1 | ForEach-Object { "$_" }
            $smokeCode = $LASTEXITCODE
            $ErrorActionPreference = $previous
            if ($smokeCode -ne 0) { throw "The test program failed on the runtime: $($out -join ' ')" }
            Write-Host (@($out)[-1]) -ForegroundColor Green
        } finally {
            Remove-Item $smoke -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    if ($ZipOut) {
        if (Test-Path $ZipOut) { throw "$ZipOut exists already." }
        # entry by entry with forward slashes, as package-mysql.ps1 does (Compress-Archive of Windows PowerShell 5.1 writes backslashes)
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
