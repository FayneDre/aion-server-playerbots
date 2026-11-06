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
      tell apart from the last, and the launcher shows the notes in the player's language;
    - a deployed jar whose revision is -DIRTY, or is not HEAD with nothing but docs and tools committed since (the patch note is written after the deploy):
      twice a deploy from a clean tree kept the previous -DIRTY jar, because Maven repackages nothing when no source changed;
    - an entry, in either language, that does not name that revision, which is the one //bot version will show the testers.

    Once tagged, writes into release/<tag>/ (ignored by git, out of reach of mvn clean) the release body (the English entry, then the French one folded in a <details> block)
    and the two entries as notes.en.md and notes.fr.md, the files the launcher reads, then prints the gh command that publishes them.

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
if (-not $ServerRoot) { throw 'No server path. Set AION_SERVER_HOME or pass -ServerRoot: the deployed jar is what this checks.' }

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
    if (-not $jar) { throw "No game-server jar under $ServerRoot\game-server\libs. Deploy first." }
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
    throw "The deployed jar's manifest names no revision."
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

    $deployed = Get-DeployedRevision
    if ($deployed -match 'DIRTY') {
        throw "The deployed jar is revision $deployed. Delete game-server\target\*.jar and commons\target\*.jar, run deploy.ps1 -Restart from this clean tree, then tag."
    }
    $head = git rev-parse --short=7 HEAD # the length the jar's manifest uses (git-commit-id-maven-plugin's default)
    if ($deployed -ne $head) {
        git cat-file -e "$deployed^{commit}" 2>$null
        if ($LASTEXITCODE -ne 0) { throw "The deployed jar is revision $deployed, which this repository does not know. Deploy from here first." }
        # what reaches a server: the modules' sources, handlers and poms; docs and tools do not
        $since = @(git diff --name-only $deployed HEAD | Where-Object { $_ -match '^(game-server|commons|chat-server)/|(^|/)pom\.xml$' -and $_ -notlike '*.md' })
        if ($since) {
            throw "The deployed jar is revision $deployed, and code changed since ($($since -join ', ')). Deploy HEAD, then tag."
        }
    }

    $entries = @{}
    foreach ($language in @(@{ Name = 'fr'; File = 'current.md' }, @{ Name = 'en'; File = 'current.en.md' })) {
        $entry = Get-Entry $language.File $tag
        if (-not $entry) {
            throw "docs/patch-notes/$($language.File) has no entry for this patch. Add a section headed '## $tag' in both files, commit them, and run this again."
        }
        if ($entry -notmatch [regex]::Escape($deployed)) {
            throw "The entry '## $tag' of docs/patch-notes/$($language.File) does not name revision $deployed, which //bot version will show. Write it in the entry."
        }
        $entries[$language.Name] = $entry
    }

    $docProblems = & powershell -NoProfile -File (Join-Path $PSScriptRoot 'check-docs.ps1')
    if ($docProblems) {
        Write-Host 'Docs (tools/check-docs.ps1), not blocking:' -ForegroundColor Yellow
        $docProblems | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
    }

    if ($Check) {
        Write-Host "Every check passed: $tag can be made on $head (deployed revision $deployed)." -ForegroundColor Green
        return
    }
    if (-not $Message) { $Message = git log -1 --format=%s }
    git tag -a $tag -m $Message
    if ($LASTEXITCODE -ne 0) { throw "git tag failed" }

    # The entries without their heading: GitHub shows the tag as the release's title.
    $bodyEn = ($entries.en -replace '^## [^\n]*\n', '').Trim()
    $bodyFr = ($entries.fr -replace '^## [^\n]*\n', '').Trim()
    $out = Join-Path $repoRoot "release\$tag" # not under a target folder: a deploy from a clean tree runs mvn clean
    New-Item -ItemType Directory -Force $out | Out-Null
    $utf8 = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $out 'notes.en.md'), "$bodyEn`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $out 'notes.fr.md'), "$bodyFr`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $out 'body.md'), "$bodyEn`n`n<details>`n<summary>Fran$([char]0xE7)ais</summary>`n`n$bodyFr`n`n</details>`n", $utf8)

    if ($Push) {
        git push origin $tag
        if ($LASTEXITCODE -ne 0) { throw "git push of $tag failed; the tag exists locally" }
        Write-Host "Tagged $head as $tag and pushed it." -ForegroundColor Green
    } else {
        Write-Host "Tagged $head as $tag, not pushed. To push it: git push origin $tag" -ForegroundColor Green
    }
    $flag = if ($preRelease) { ' --prerelease' } else { '' }
    Write-Host "Release notes written to $out. Once the tag is pushed, to publish the release:"
    Write-Host "  gh release create $tag$flag --verify-tag --title $tag --notes-file `"$out\body.md`" `"$out\notes.en.md`" `"$out\notes.fr.md`""
    if (-not $preRelease) {
        Write-Host 'A stable release carries the English in full and a short French summary: shorten the French part of body.md first unless both are short.' -ForegroundColor Yellow
    }
    Write-Host "//bot version on the server should say revision $deployed."
} finally {
    Pop-Location
}
