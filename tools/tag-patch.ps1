<#
.SYNOPSIS
    Tags the commit a test patch or a release is built from as vX.Y.Z-beta.N or vX.Y.Z, and writes its GitHub release notes. Pushes the tag only
    with -Push; publishing the release is left to the user.

.DESCRIPTION
    A patch is named by a semantic version, as the launcher's are (docs/architecture/plans/release-process-plan.md): vX.Y.Z-beta.N is a test patch,
    published as a GitHub pre-release from develop; vX.Y.Z a stable release from main. The date tags patch-YYYY-MM-DD made before stay as history.
    The patch note names the tag; //bot version in game names the revision, which is the deployed jar's.

    Refuses, each time saying why:
    - a version that is not X.Y.Z or X.Y.Z-beta.N, that is tagged already, or that is not above the highest version tagged;
    - a beta made from another branch than develop, a release from another branch than main;
    - a dirty tree: a build from uncommitted code has a revision ending in -DIRTY, which matches nothing anyone can look at afterwards;
    - docs/patch-notes/current.md (French) or current.en.md (English) with no entry for the tag: a patch nobody wrote up is one a tester cannot
      tell apart from the last, and the launcher shows the notes in the player's language.

    The entries name no revision: the bundle is built from the tagged commit, so the revision //bot version shows the testers is that commit's,
    which exists only once the notes are committed. The published notes get it as their first line. The server deployed here is only looked at:
    a jar of another revision, or -DIRTY, is said (what was tried in game is then not exactly what ships), never refused.

    Once tagged, writes into release/<tag>/ (ignored by git, out of reach of mvn clean) the release body (the English entry, then the French one folded in a <details> block)
    and the two entries as notes.en.md and notes.fr.md, the files the launcher reads; builds from the tagged commit the bundle a beta launcher installs
    (package-release.ps1: the zip, its manifest.json, the navmesh maps rebuilt since the previous bundle); then prints the gh command that publishes them all.

.PARAMETER Version
    The version to tag, with or without its leading v: 0.1.0-beta.1, 0.1.0.

.PARAMETER Message
    What the patch is, for the tag's annotation. Defaults to the subject of the tagged commit.

.PARAMETER Push
    Push the tag to origin once made.

.PARAMETER Check
    Run every check and stop before tagging.

.PARAMETER ServerRoot
    The server installation whose deployed jar is checked. Defaults to AION_SERVER_HOME.

.EXAMPLE
    .\tools\tag-patch.ps1 -Version 0.1.0-beta.1 -Check
    .\tools\tag-patch.ps1 -Version 0.1.0-beta.2 -Message "Fixes from the feedback of 14 November" -Push
#>
param(
    [Parameter(Mandatory)][string]$Version,
    [string]$Message,
    [switch]$Push,
    [switch]$Check,
    [string]$ServerRoot = $env:AION_SERVER_HOME
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $ServerRoot) { $ServerRoot = [Environment]::GetEnvironmentVariable('AION_SERVER_HOME', 'User') }
if (-not $ServerRoot) { throw 'No server path. Set AION_SERVER_HOME or pass -ServerRoot: the bundle and the deployed jar are read there.' }

$versionPattern = '^v?(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$'

# A key that sorts versions as semantic versioning does: a beta sorts before the release it leads to.
function Get-VersionKey([string]$text) {
    $m = [regex]::Match($text, $versionPattern)
    if (-not $m.Success) { return $null }
    $beta = if ($m.Groups[4].Success) { [int]$m.Groups[4].Value } else { [int]::MaxValue }
    return '{0:D6}.{1:D6}.{2:D6}.{3:D10}' -f [int]$m.Groups[1].Value, [int]$m.Groups[2].Value, [int]$m.Groups[3].Value, $beta
}

function Get-DeployedRevision {
    $jar = Get-ChildItem (Join-Path $ServerRoot 'game-server\libs\game-server-*.jar') -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' } | Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $jar) { return $null }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $reader = New-Object IO.StreamReader($zip.GetEntry('META-INF/MANIFEST.MF').Open())
        $manifest = $reader.ReadToEnd()
        $reader.Dispose()
    } finally {
        $zip.Dispose()
    }
    if ($manifest -match '(?m)^Revision: (\S+)') { return $Matches[1] }
    return $null
}

# The entry headed '## <tag>' of a notes file, heading included, up to the next entry or the separator before it.
function Get-Entry([string]$file, [string]$tag) {
    $path = Join-Path $repoRoot "docs\patch-notes\$file"
    if (-not (Test-Path $path)) { return $null }
    $text = [IO.File]::ReadAllText($path).Replace("`r`n", "`n")
    $m = [regex]::Match($text, "(?ms)^## $([regex]::Escape($tag))( [^\n]*)?\n.*?(?=^## |\z)")
    if (-not $m.Success) { return $null }
    return ($m.Value -replace '(?s)\n-{3,}\s*$', '').TrimEnd()
}

Push-Location $repoRoot
try {
    $key = Get-VersionKey $Version
    if (-not $key) { throw "'$Version' is not a version: write X.Y.Z for a release or X.Y.Z-beta.N for a test patch." }
    $tag = 'v' + $Version.TrimStart('v')
    $preRelease = $tag -match '-beta\.'

    git rev-parse -q --verify "refs/tags/$tag" *> $null
    if ($LASTEXITCODE -eq 0) { throw "$tag is tagged already." }
    $highest = @(git tag --list 'v*' | Where-Object { Get-VersionKey $_ } | Sort-Object { Get-VersionKey $_ }) | Select-Object -Last 1
    if ($highest -and (Get-VersionKey $highest) -ge $key) { throw "$tag is not above $highest, the highest version tagged." }

    $branch = git rev-parse --abbrev-ref HEAD
    $wanted = if ($preRelease) { 'develop' } else { 'main' }
    if ($branch -ne $wanted) { throw "$tag is made from $wanted, and this is $branch." }

    $dirty = git status --porcelain
    if ($dirty) { throw "The working tree has uncommitted changes. Commit them, then deploy and tag from a clean tree." }

    $head = git rev-parse --short=7 HEAD # the length the jar's manifest uses (git-commit-id-maven-plugin's default)
    # What was tried in game: said when it is not what ships, never refused (the bundle is built from the tag whatever runs here).
    $deployed = Get-DeployedRevision
    $deployedNote = $null
    if (-not $deployed) {
        $deployedNote = "no deployed jar naming its revision under $ServerRoot"
    } elseif ($deployed -match 'DIRTY') {
        $deployedNote = "the server here runs $deployed, built from uncommitted code; what ships is $head"
    } elseif ($deployed -ne $head) {
        git cat-file -e "$deployed^{commit}" 2>$null
        if ($LASTEXITCODE -ne 0) {
            $deployedNote = "the server here runs $deployed, a revision this repository does not know"
        } else {
            # what reaches a server: the modules' sources, handlers and poms; docs and tools do not
            $since = @(git diff --name-only $deployed HEAD | Where-Object { $_ -match '^(game-server|commons|chat-server)/|(^|/)pom\.xml$' -and $_ -notlike '*.md' })
            if ($since) { $deployedNote = "the server here runs $deployed; code changed since, not tried in game: $($since -join ', ')" }
        }
    }
    if ($deployedNote) { Write-Host "Not blocking: $deployedNote." -ForegroundColor Yellow }

    $entries = @{}
    foreach ($language in @(@{ Name = 'fr'; File = 'current.md' }, @{ Name = 'en'; File = 'current.en.md' })) {
        $entry = Get-Entry $language.File $tag
        if (-not $entry) {
            throw "docs/patch-notes/$($language.File) has no entry for this patch. Add a section headed '## $tag' in both files, commit them, and run this again."
        }
        $entries[$language.Name] = $entry
    }

    try {
        $docProblems = & powershell -NoProfile -File (Join-Path $PSScriptRoot 'check-docs.ps1')
    } catch {
        $docProblems = @("the check itself failed: $($_.Exception.Message)")
    }
    if ($docProblems) {
        Write-Host 'Docs (tools/check-docs.ps1), not blocking:' -ForegroundColor Yellow
        $docProblems | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
    }

    if ($Check) {
        Write-Host "Every check passed: $tag can be made on $head, the revision //bot version will show the testers." -ForegroundColor Green
        return
    }
    if (-not $Message) { $Message = git log -1 --format=%s }
    git tag -a $tag -m $Message
    if ($LASTEXITCODE -ne 0) { throw "git tag failed" }

    # The entries without their heading (GitHub shows the tag as the release's title), led by the revision the bundle carries
    $bodyEn = "Revision ``$head``, the one ``//bot version`` shows.`n`n" + ($entries.en -replace '^## [^\n]*\n', '').Trim()
    $bodyFr = "R$([char]0xE9)vision ``$head``, celle que ``//bot version`` affiche.`n`n" + ($entries.fr -replace '^## [^\n]*\n', '').Trim()
    $out = Join-Path $repoRoot "release\$tag" # not under a target folder: a deploy from a clean tree runs mvn clean
    New-Item -ItemType Directory -Force $out | Out-Null
    $utf8 = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $out 'notes.en.md'), "$bodyEn`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $out 'notes.fr.md'), "$bodyFr`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $out 'body.md'), "$bodyEn`n`n<details>`n<summary>Fran$([char]0xE7)ais</summary>`n`n$bodyFr`n`n</details>`n", $utf8)

    # The bundle a beta launcher installs, built from the tagged commit (docs/architecture/plans/release-process-plan.md, milestone 6)
    & (Join-Path $PSScriptRoot 'package-release.ps1') -Version $tag -ServerRoot $ServerRoot

    if ($Push) {
        git push origin $tag
        if ($LASTEXITCODE -ne 0) { throw "git push of $tag failed; the tag exists locally" }
        Write-Host "Tagged $head as $tag and pushed it." -ForegroundColor Green
    } else {
        Write-Host "Tagged $head as $tag, not pushed. To push it: git push origin $tag" -ForegroundColor Green
    }
    $flag = if ($preRelease) { ' --prerelease' } else { '' }
    Write-Host "Release notes written to $out. Once the tag is pushed, to publish the release:"
    $assets = @('notes.en.md', 'notes.fr.md', 'manifest.json', "aion-server-$tag.zip") | ForEach-Object { Join-Path $out $_ }
    $assets += @(Get-ChildItem $out -Filter 'navmesh-*.gz' | ForEach-Object { $_.FullName })
    $quoted = ($assets | ForEach-Object { "`"$_`"" }) -join ' '
    Write-Host "  gh release create $tag$flag --repo FayneDre/aion-server-playerbots --verify-tag --title $tag --notes-file `"$out\body.md`" $quoted"
    if (-not $preRelease) {
        Write-Host 'A stable release carries the English in full and a short French summary: shorten the French part of body.md first unless both are short.' -ForegroundColor Yellow
    }
    Write-Host "//bot version on a server installed from this bundle says revision $head."
} finally {
    Pop-Location
}
