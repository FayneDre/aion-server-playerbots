<#
.SYNOPSIS
    Installs the git hooks of this repository into .git/hooks, and the list of names they keep out.

.DESCRIPTION
    Two rules of this project were kept by habit and broken by it: no co-author trailer on a commit, and nobody named in the code, the docs or the commit
    messages. The hooks in tools/hooks keep them mechanically. They are copied rather than linked because .git/hooks is not versioned and a hook must
    run with unix line endings, which a checkout on Windows does not give a tracked file.

    The names to refuse live in .git/name-blocklist.txt, which this creates empty on the first run and never overwrites. It is not in the repository on
    purpose: a list of names to keep out of the repository would be a leak of its own. Add one name per line.

.EXAMPLE
    .\tools\install-hooks.ps1
    Add-Content .git\name-blocklist.txt "somebody"
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$gitDir = (& git -C $root rev-parse --git-dir).Trim()
if (-not [System.IO.Path]::IsPathRooted($gitDir)) {
    $gitDir = Join-Path $root $gitDir
}
$hooksDir = Join-Path $gitDir 'hooks'
New-Item -ItemType Directory -Force -Path $hooksDir | Out-Null

foreach ($source in Get-ChildItem (Join-Path $PSScriptRoot 'hooks') -File) {
    $text = [System.IO.File]::ReadAllText($source.FullName) -replace "`r`n", "`n"
    $target = Join-Path $hooksDir $source.Name
    [System.IO.File]::WriteAllText($target, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "installed $($source.Name)" -ForegroundColor Green
}

$blocklist = Join-Path $gitDir 'name-blocklist.txt'
if (-not (Test-Path $blocklist)) {
    New-Item -ItemType File -Path $blocklist | Out-Null
    Write-Host "created $blocklist (empty: add one name per line)" -ForegroundColor Yellow
} else {
    Write-Host "kept $blocklist ($((Get-Content $blocklist | Where-Object { $_ }).Count) name(s))" -ForegroundColor DarkGray
}
