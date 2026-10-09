<#
.SYNOPSIS
    Names every javadoc block in the playerbot module that is followed directly by another one.

.DESCRIPTION
    Two doc comments in a row mean one of them describes nothing: a member was removed or moved and its comment stayed behind. The reader then
    attributes it to whatever comes next, which is worse than no comment -- twenty nine of them were found at once, among them "toggles
    autonomy" sitting on //bot kind and "casts the best offensive skill" on the heal. javac does not notice and neither does a reviewer.

    Written in PowerShell for the same reason as check-bot-docs.ps1: it is called from deploy.ps1, where bash is not on the path.

    Prints file:line for each orphan and exits 1 when there is one, exits 0 otherwise.
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$module = Join-Path $root 'game-server\src\com\aionemu\gameserver\playerbot'
if (-not (Test-Path $module)) { exit 0 }

$found = foreach ($file in Get-ChildItem -Path $module -Filter *.java -Recurse) {
    $lines = [System.IO.File]::ReadAllLines($file.FullName)
    $lastEnd = -5
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $text = $lines[$i].Trim()
        if ($text.StartsWith('/**') -and $lastEnd -eq $i - 1) {
            "$($file.FullName.Substring($root.Length + 1)):$($lastEnd + 1)"
        }
        # a one line block opens and closes on the same line
        if ($text.EndsWith('*/') -and ($text.StartsWith('/**') -or $text.StartsWith('*') -or $text.StartsWith('/*'))) { $lastEnd = $i }
        elseif ($text.Length -gt 0 -and -not ($text.StartsWith('*') -or $text.StartsWith('/*'))) { $lastEnd = -1 }
    }
}
if (-not $found) { exit 0 }
$found
exit 1
