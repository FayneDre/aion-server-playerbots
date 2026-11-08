<#
.SYNOPSIS
    Builds the server bundle of a version: the zip a beta launcher installs, its manifest, and the navmesh maps rebuilt since the last bundle.

.DESCRIPTION
    What a tester installs is what Maven's assembly puts in each server's zip (game-server/assembly.xml: config, data, the start scripts, every
    library with the project's own jars), the three zips merged, plus game-server/sql/update.sql and tools/apply-sql.ps1, which the launcher runs
    from the bundle so the rules of the SQL live in one place (docs/architecture/plans/release-process-plan.md, milestone 6).

    Writes into release/<tag>/:
    - aion-server-<tag>.zip, holding manifest.json too;
    - manifest.json: version, revision, launcher API level, the Java it needs, the SHA-256 of every file of the zip, the rules the launcher
      applies (which folders hold only what the bundle lists, which files are the person's to edit), the bundle's own hash, and the navmesh;
    - navmesh-<file>.gz for each map file (.nav, .glide, .ride, .flight of the install's data/navmesh) whose hash differs from the previous
      bundle's: the manifest names, for every map file, the release that carries it, so a tester who skips versions still gets every map.

    Refuses a dirty tree (the revision would end in -DIRTY), a HEAD that is not the version's tag (unless -Trial), a file of config or data that
    git does not track (the assembly reads the working tree), and a jar whose manifest names another release or revision.

.PARAMETER Version
    The version, with or without its v: 0.1.0-beta.2.

.PARAMETER ServerRoot
    The install whose data/navmesh holds the maps. Defaults to AION_SERVER_HOME.

.PARAMETER Previous
    The tag of the previous bundle, whose manifest says which maps are unchanged. Defaults to the highest version below this one whose GitHub
    release carries a manifest.json; none means every map is attached.

.PARAMETER Trial
    Build from a HEAD that is not tagged, into release/trial-<tag>: to try the script, never to publish.

.EXAMPLE
    .\tools\package-release.ps1 -Version 0.1.0-beta.2 -Trial
#>
param(
    [Parameter(Mandatory)][string]$Version,
    [string]$ServerRoot = $env:AION_SERVER_HOME,
    [string]$Previous,
    [switch]$Trial
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$repository = 'FayneDre/aion-server-playerbots'
$versionPattern = '^v?(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$'
if (-not $ServerRoot) { $ServerRoot = [Environment]::GetEnvironmentVariable('AION_SERVER_HOME', 'User') }
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

function Get-VersionKey([string]$text) {
    $m = [regex]::Match($text, $versionPattern)
    if (-not $m.Success) { return $null }
    $beta = if ($m.Groups[4].Success) { [int]$m.Groups[4].Value } else { [int]::MaxValue }
    return '{0:D6}.{1:D6}.{2:D6}.{3:D10}' -f [int]$m.Groups[1].Value, [int]$m.Groups[2].Value, [int]$m.Groups[3].Value, $beta
}

function Get-Sha256([string]$path) { (Get-FileHash -Algorithm SHA256 $path).Hash.ToLowerInvariant() }

function Read-JarManifest([string]$jar) {
    $zip = [IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $reader = New-Object IO.StreamReader($zip.GetEntry('META-INF/MANIFEST.MF').Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
}

# The previous bundle's manifest, from its GitHub release; null when there is none.
function Get-PreviousManifest([string]$tag) {
    $dir = Join-Path $env:TEMP "aion-previous-manifest-$PID"
    Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $dir | Out-Null
    try {
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        gh release download $tag --repo $repository --pattern manifest.json --dir $dir 2>$null | Out-Null
        $ErrorActionPreference = $previousPreference
        $file = Join-Path $dir 'manifest.json'
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $file)) { return $null }
        return [IO.File]::ReadAllText($file) | ConvertFrom-Json
    } finally { Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue }
}

Push-Location $repoRoot
try {
    $key = Get-VersionKey $Version
    if (-not $key) { throw "'$Version' is not a version: X.Y.Z or X.Y.Z-beta.N." }
    $tag = 'v' + $Version.TrimStart('v')
    if (git status --porcelain) { throw 'The working tree has uncommitted changes: the bundle would carry a -DIRTY revision. Commit first.' }
    $head = (git rev-parse --short=7 HEAD).Trim()
    if (-not $Trial) {
        $tagged = (git rev-parse -q --verify "refs/tags/$tag^{commit}" 2>$null)
        if (-not $tagged -or $tagged.Substring(0, 7) -ne $head) { throw "HEAD ($head) is not $tag. Tag it first (tag-patch.ps1), or try with -Trial." }
    }
    $out = Join-Path $repoRoot ("release\" + $(if ($Trial) { "trial-$tag" } else { $tag }))
    New-Item -ItemType Directory -Force $out | Out-Null
    Get-ChildItem $out -Filter 'navmesh-*.gz' | Remove-Item
    Remove-Item (Join-Path $out 'aion-server-*.zip'), (Join-Path $out 'manifest.json') -ErrorAction SilentlyContinue

    Write-Host "Building $tag ($head)..." -ForegroundColor Cyan
    # quoted: PowerShell would split -Daion.release=... on the dot
    & mvn -q -pl game-server,login-server,chat-server -am clean package "-Daion.release=$tag"
    if ($LASTEXITCODE -ne 0) { throw "Build failed (exit code $LASTEXITCODE)" }

    $staging = Join-Path $env:TEMP "aion-bundle-$PID"
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory $staging | Out-Null
    try {
        foreach ($module in 'game-server', 'login-server', 'chat-server') {
            # each zip holds its own top folder: game-server/..., login-server/..., chat-server/...
            [IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $repoRoot "$module\target\$module.zip"), $staging)
        }
        New-Item -ItemType Directory -Force (Join-Path $staging 'tools'), (Join-Path $staging 'game-server\sql') | Out-Null
        # at the same place as in the repository, so the script finds update.sql by its default path
        Copy-Item (Join-Path $repoRoot 'tools\apply-sql.ps1') (Join-Path $staging 'tools\apply-sql.ps1')
        Copy-Item (Join-Path $repoRoot 'game-server\sql\update.sql') (Join-Path $staging 'game-server\sql\update.sql')

        $tracked = @{}
        foreach ($path in git ls-files -- '*/config/*' '*/data/*') { $tracked[$path] = $true }
        $files = [ordered]@{}
        foreach ($file in Get-ChildItem $staging -Recurse -File | Sort-Object FullName) {
            $relative = $file.FullName.Substring($staging.Length + 1).Replace('\', '/')
            if ($relative -match '^[^/]+/(config|data)/' -and -not $tracked[$relative]) { throw "$relative is in the bundle and git does not track it: the assembly read it from the working tree." }
            $files[$relative] = Get-Sha256 $file.FullName
        }

        $gameJar = Get-ChildItem (Join-Path $staging 'game-server\libs') -Filter 'game-server-*.jar' | Select-Object -First 1
        $jarManifest = Read-JarManifest $gameJar.FullName
        if ($jarManifest -notmatch "(?m)^Implementation-Version: $([regex]::Escape($tag))\s*$") { throw "The game server jar does not name $tag as its release." }
        if ($jarManifest -notmatch "(?m)^Revision: $head\s*$") { throw "The game server jar does not name revision $head." }
        $api = [regex]::Match((Get-Content -Raw 'game-server\src\com\aionemu\gameserver\AdminChannel.java'), 'public static final int API_LEVEL = (\d+);')
        $addonApi = [regex]::Match((Get-Content -Raw 'game-server\src\com\aionemu\gameserver\playerbot\addon\AddonWebServer.java'), 'public static final int API_LEVEL = (\d+);')
        $java = [regex]::Match((Get-Content -Raw 'pom.xml'), '<maven.compiler.release>(\d+)</maven.compiler.release>')
        if (-not $api.Success -or -not $addonApi.Success -or -not $java.Success) {
            throw 'Could not read the API levels (AdminChannel.java, AddonWebServer.java) or the Java release (pom.xml).'
        }

        # ---- the navmesh: every map file, and the release that carries it ----------------------------------------------------------------
        if (-not $Previous) {
            $below = @(git tag --list 'v*' | Where-Object { (Get-VersionKey $_) -and (Get-VersionKey $_) -lt $key } | Sort-Object { Get-VersionKey $_ } -Descending)
            foreach ($candidate in $below) {
                $previousManifest = Get-PreviousManifest $candidate
                if ($previousManifest) { $Previous = $candidate; break }
            }
        } else {
            $previousManifest = Get-PreviousManifest $Previous
            if (-not $previousManifest) { throw "The release $Previous carries no manifest.json." }
        }
        $navmesh = [ordered]@{}
        $attached = 0
        $navDir = Join-Path $ServerRoot 'game-server\data\navmesh'
        if (-not (Test-Path $navDir)) { throw "No navmesh in ${navDir}: the bundle names the maps of the install it is made from." }
        foreach ($file in Get-ChildItem $navDir -File | Where-Object { $_.Extension -in '.nav', '.glide', '.ride', '.flight' } | Sort-Object Name) {
            $path = "game-server/data/navmesh/$($file.Name)"
            $hash = Get-Sha256 $file.FullName
            $asset = "navmesh-$($file.Name).gz"
            $before = if ($previousManifest -and $previousManifest.navmesh) { $previousManifest.navmesh.$path } else { $null }
            if ($before -and $before.sha256 -eq $hash) {
                $navmesh[$path] = [ordered]@{ sha256 = $hash; size = $file.Length; release = $before.release; asset = $before.asset }
                continue
            }
            $source = [IO.File]::OpenRead($file.FullName)
            $target = [IO.File]::Create((Join-Path $out $asset))
            $gzip = New-Object IO.Compression.GZipStream($target, [IO.Compression.CompressionLevel]::Optimal)
            try { $source.CopyTo($gzip) } finally { $gzip.Dispose(); $target.Dispose(); $source.Dispose() }
            $navmesh[$path] = [ordered]@{ sha256 = $hash; size = $file.Length; release = $tag; asset = $asset }
            $attached++
        }

        $manifest = [ordered]@{
            source   = 'release'
            version  = $tag
            revision = $head
            apiLevel = [int]$api.Groups[1].Value
            # what the addons' pages offer: the launcher installs only an addons release asking for this level or lower
            addonApiLevel = [int]$addonApi.Groups[1].Value
            java     = [int]$java.Groups[1].Value
            builtAt  = (Get-Date).ToString('o')
            # folders that hold only what the bundle lists (a stale jar stays on the classpath, a removed handler is still compiled), and the
            # files that are the person's to edit: one edited by hand is left as it is, the new one put beside it
            rules    = [ordered]@{
                prune = @('game-server/libs/', 'login-server/libs/', 'chat-server/libs/', 'game-server/data/handlers/', 'game-server/data/static_data/')
                local = @('game-server/config/', 'login-server/config/', 'chat-server/config/', 'game-server/start.bat', 'game-server/start.sh',
                    'login-server/start.bat', 'login-server/start.sh', 'chat-server/start.bat', 'chat-server/start.sh')
            }
            files    = $files
            navmesh  = $navmesh
        }
        $utf8 = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText((Join-Path $staging 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6) + "`n", $utf8)

        $zipPath = Join-Path $out "aion-server-$tag.zip"
        # entry by entry: names with forward slashes whatever the .NET version, which every unzip reads
        $archive = [IO.Compression.ZipFile]::Open($zipPath, 'Create')
        try {
            foreach ($file in Get-ChildItem $staging -Recurse -File) {
                $name = $file.FullName.Substring($staging.Length + 1).Replace('\', '/')
                [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $file.FullName, $name, [IO.Compression.CompressionLevel]::Optimal)
            }
        } finally { $archive.Dispose() }
    } finally {
        Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    }

    $zipItem = Get-Item $zipPath
    $manifest['bundle'] = [ordered]@{ name = $zipItem.Name; sha256 = (Get-Sha256 $zipItem.FullName); size = $zipItem.Length }
    [IO.File]::WriteAllText((Join-Path $out 'manifest.json'), ($manifest | ConvertTo-Json -Depth 6) + "`n", $utf8)

    Write-Host ("Bundle {0}: {1} files, {2:N0} MB zipped." -f $zipItem.Name, $files.Count, ($zipItem.Length / 1MB)) -ForegroundColor Green
    $previousText = if ($Previous) { "unchanged since $Previous" } else { 'no previous bundle' }
    Write-Host "Navmesh: $($navmesh.Count) map files, $attached attached ($previousText for the others)."
    # a gh that found no manifest on an older release would otherwise be the script's exit code
    $global:LASTEXITCODE = 0
} finally {
    Pop-Location
}
