<#
.SYNOPSIS
    Checks the docs folder: broken links, documents missing from the index, plans with no Status line, files over 200 lines, and plans whose code
    changed since the last patch while the plan did not.

.DESCRIPTION
    The rules were written in CLAUDE.md and kept by discipline, which did not hold: on 27 October ten plans had a status days out of date, one of them
    saying "nothing here is built yet" about four built milestones. The same lesson as check-bot-docs.ps1: what a script can see, a script checks.

    Errors (exit 1): a link to a file that does not exist, a document docs/README.md does not link to, a *-plan.md with no Status line in its first lines.
    Warnings (exit 0 if nothing else): a document over 200 lines, the monthly patch note archives excepted; and a plan that names, in backticks, a class
    of the bot module changed since the last patch tag, while the plan itself was not touched. That last one is a question rather than a fault: the
    change may not concern the plan, but it is the moment its Status line goes stale, and nobody else asks.

    Called by deploy.ps1 and tag-patch.ps1, which print what it says and carry on.

.PARAMETER Since
    The tag or commit the changed classes are counted from. Defaults to the last patch tag.
#>
param([string]$Since)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$docs = Join-Path $root 'docs'
$index = Join-Path $docs 'README.md'
$errors = @()
$warnings = @()

function Get-Relative([string]$path) { $path.Substring($root.Length + 1).Replace('\', '/') }

$files = @(Get-ChildItem $docs -Recurse -Filter '*.md') + @(Get-Item (Join-Path $root 'CLAUDE.md'))
$linkPattern = '\]\(([^)#\s]+)(#[^)]*)?\)'

foreach ($file in $files) {
    $text = [IO.File]::ReadAllText($file.FullName)
    foreach ($match in [regex]::Matches($text, $linkPattern)) {
        $target = $match.Groups[1].Value
        if ($target -match '^[a-z]+:') { continue } # http, https, mailto
        $resolved = Join-Path $file.DirectoryName ($target.Replace('/', '\'))
        if (-not (Test-Path $resolved)) { $errors += "$(Get-Relative $file.FullName): broken link to $target" }
    }
    if ($file.DirectoryName.StartsWith($docs)) {
        $lines = ([regex]::Matches($text, "`n")).Count + [int](-not $text.EndsWith("`n"))
        if ($lines -gt 200 -and $file.Name -notmatch '^patch-notes-\d{4}-\d{2}\.md$') {
            $warnings += "$(Get-Relative $file.FullName): $lines lines, over the 200 the conventions allow; split it by topic"
        }
        if ($file.Name -like '*-plan.md') {
            $head = ($text -split "`n" | Select-Object -First 8) -join "`n"
            if ($head -notmatch '(?m)^\**Status') { $errors += "$(Get-Relative $file.FullName): a plan with no Status line at its top" }
        }
    }
}

# every document is reachable from the index
$indexText = [IO.File]::ReadAllText($index)
$indexed = @{}
foreach ($match in [regex]::Matches($indexText, $linkPattern)) {
    $indexed[[IO.Path]::GetFullPath((Join-Path $docs ($match.Groups[1].Value.Replace('/', '\'))))] = $true
}
foreach ($file in $files | Where-Object { $_.DirectoryName.StartsWith($docs) -and $_.FullName -ne $index }) {
    if (-not $indexed[$file.FullName]) { $errors += "$(Get-Relative $file.FullName): not listed in docs/README.md" }
}

# plans whose classes changed since the last patch, while the plan did not
Push-Location $root
try {
    $lastTag = if ($Since) { $Since } else { git describe --tags --match 'patch-*' --abbrev=0 2>$null }
    if ($lastTag) {
        $changed = @(git diff --name-only "$lastTag" HEAD -- 'game-server/src/com/aionemu/gameserver/playerbot') + @(git diff --name-only HEAD -- 'game-server/src/com/aionemu/gameserver/playerbot')
        $classes = @($changed | Where-Object { $_ -like '*.java' } | ForEach-Object { [IO.Path]::GetFileNameWithoutExtension($_) } | Sort-Object -Unique)
        # a doc not yet committed counts as touched too: a plan written today names classes changed today, and is not stale for it
        $touched = @(git diff --name-only "$lastTag" HEAD -- docs) + @(git diff --name-only HEAD -- docs) + @(git ls-files --others --exclude-standard -- docs) |
            ForEach-Object { $_.Replace('/', '\') }
        foreach ($plan in $files | Where-Object { $_.Name -like '*-plan.md' }) {
            if ($touched -contains (Get-Relative $plan.FullName).Replace('/', '\')) { continue }
            $text = [IO.File]::ReadAllText($plan.FullName)
            $named = @($classes | Where-Object { $text -match ('`' + $_ + '[`.(#]') })
            if ($named) {
                $warnings += "$(Get-Relative $plan.FullName): names $($named -join ', '), changed since $lastTag; is its Status still true?"
            }
        }
    }
} finally {
    Pop-Location
}

foreach ($line in $errors) { Write-Output "ERROR   $line" }
foreach ($line in $warnings) { Write-Output "WARNING $line" }
if ($errors) { exit 1 }
exit 0
