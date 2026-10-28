<#
.SYNOPSIS
    Tags the commit a test patch is built from, so a report can name the exact code it was about. Pushes the tag only with -Push.

.DESCRIPTION
    Testers name a patch by the hour it went out ("Patch 20h"), which no one can check out. This tags HEAD patch-YYYY-MM-DD, or patch-YYYY-MM-DD-2 and on
    for another patch the same day. The patch note names the tag; //bot version in game names the revision, which is the deployed jar's.

    Refuses, each time saying why:
    - a dirty tree: a build from uncommitted code has a revision ending in -DIRTY, which matches nothing anyone can look at afterwards;
    - docs/patch-notes/current.md with no entry for the tag: a patch nobody wrote up is one a tester cannot tell apart from the last;
    - a deployed jar whose revision is -DIRTY, or is not HEAD with nothing but docs and tools committed since (the patch note is written after the deploy):
      twice a deploy from a clean tree kept the previous -DIRTY jar, because Maven repackages nothing when no source changed;
    - a patch note entry that does not name that revision, which is the one //bot version will show the testers.

    Pushing is the user's decision, never this script's default: without -Push the tag stays local and the line to push it is printed.

.PARAMETER Message
    What the patch is, for the tag's annotation. Defaults to the subject of the tagged commit.

.PARAMETER Push
    Push the tag to origin once made.

.PARAMETER Check
    Run every check and stop before tagging.

.PARAMETER ServerRoot
    The server installation whose deployed jar is checked. Defaults to AION_SERVER_HOME.

.EXAMPLE
    .\tools\tag-patch.ps1
    .\tools\tag-patch.ps1 -Message "Fixes from the feedback of 14 October" -Push
#>
param(
    [string]$Message,
    [switch]$Push,
    [switch]$Check,
    [string]$ServerRoot = $env:AION_SERVER_HOME
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $ServerRoot) { $ServerRoot = [Environment]::GetEnvironmentVariable('AION_SERVER_HOME', 'User') }
if (-not $ServerRoot) { throw 'No server path. Set AION_SERVER_HOME or pass -ServerRoot: the deployed jar is what this checks.' }

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

Push-Location $repoRoot
try {
    $dirty = git status --porcelain
    if ($dirty) { throw "The working tree has uncommitted changes. Commit them, then deploy and tag from a clean tree." }

    $date = Get-Date -Format 'yyyy-MM-dd'
    $existing = @(git tag --list "patch-$date*")
    $tag = if ($existing.Count -eq 0) { "patch-$date" } else { "patch-$date-$($existing.Count + 1)" }
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
    $notes = Join-Path $repoRoot 'docs\patch-notes\current.md'
    $notesText = [IO.File]::ReadAllText($notes).Replace("`r`n", "`n")
    $entry = [regex]::Match($notesText, "(?ms)^## $([regex]::Escape($tag))( [^\n]*)?\n.*?(?=^## |\z)")
    if (-not $entry.Success) {
        throw "docs/patch-notes/current.md has no entry for this patch. Add a section headed '## $tag', commit it, and run this again."
    }
    if ($entry.Value -notmatch [regex]::Escape($deployed)) {
        throw "The entry '## $tag' does not name revision $deployed, which //bot version will show. Write it in the entry, as the earlier ones do."
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
    if ($Push) {
        git push origin $tag
        if ($LASTEXITCODE -ne 0) { throw "git push of $tag failed; the tag exists locally" }
        Write-Host "Tagged $head as $tag and pushed it." -ForegroundColor Green
    } else {
        Write-Host "Tagged $head as $tag, not pushed. To push it: git push origin $tag" -ForegroundColor Green
    }
    Write-Host "//bot version on the server should say revision $deployed."
} finally {
    Pop-Location
}
