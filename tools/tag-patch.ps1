<#
.SYNOPSIS
    Tags the commit a test patch is built from, and pushes the tag, so a report can name the exact code it was about.

.DESCRIPTION
    Testers name a patch by the hour it went out ("Patch 20h"), which no one can check out. This tags HEAD patch-YYYY-MM-DD, or patch-YYYY-MM-DD-2 and on
    for another patch the same day, and pushes the tag to origin. The patch note names the tag; //bot version in game names the revision, which is the
    tagged commit's when the server was built from a clean tree.

    Refuses a dirty tree: a build from uncommitted code has a revision ending in -DIRTY, which matches nothing anyone can look at afterwards.

.PARAMETER Message
    What the patch is, for the tag's annotation. Defaults to the subject of the tagged commit.

.PARAMETER NoPush
    Tag locally only.

.EXAMPLE
    .\tools\tag-patch.ps1
    .\tools\tag-patch.ps1 -Message "Fixes from the feedback of 14 October"
#>
param(
    [string]$Message,
    [switch]$NoPush
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {
    $dirty = git status --porcelain
    if ($dirty) { throw "The working tree has uncommitted changes. Commit them, then deploy and tag from a clean tree." }

    $date = Get-Date -Format 'yyyy-MM-dd'
    $existing = @(git tag --list "patch-$date*")
    $tag = if ($existing.Count -eq 0) { "patch-$date" } else { "patch-$date-$($existing.Count + 1)" }
    $revision = git rev-parse --short=7 HEAD # the length the jar's manifest uses (git-commit-id-maven-plugin's default)
    if (-not $Message) { $Message = git log -1 --format=%s }

    git tag -a $tag -m $Message
    if ($LASTEXITCODE -ne 0) { throw "git tag failed" }
    if (-not $NoPush) {
        git push origin $tag
        if ($LASTEXITCODE -ne 0) { throw "git push of $tag failed; the tag exists locally" }
    }
    Write-Host "Tagged $revision as $tag$(if ($NoPush) { ' (not pushed)' })." -ForegroundColor Green
    Write-Host "Name it in the patch note; //bot version on the server should say revision $revision."
} finally {
    Pop-Location
}
