<#
.SYNOPSIS
    Names every //bot command the handler answers that docs/bot-commands.md does not document.

.DESCRIPTION
    The list of commands lives in two places by necessity — the handler's switch and the doc's table — and nothing but habit kept them together,
    which is exactly what habit is bad at: //bot number was added and the doc was not touched until it was noticed by hand.

    Written in PowerShell rather than shell because it is called from deploy.ps1, where bash is not on the path. Finding that out cost a test and
    would otherwise have broken every deploy: a missing interpreter returns a non zero exit code, which reads exactly like an undocumented command.

    Prints the missing names and exits 1 when the two disagree, exits 0 when they agree.
#>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$handler = Join-Path $root 'game-server\data\handlers\admincommands\Bot.java'
$doc = Join-Path $root 'docs\bot-commands.md'
if (-not (Test-Path $handler) -or -not (Test-Path $doc)) { exit 0 }

$answered = Select-String -Path $handler -Pattern 'case "([a-z]+)"' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
$documented = Select-String -Path $doc -Pattern '`//bot ([a-z]+)' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique

$missing = $answered | Where-Object { $documented -notcontains $_ }
if (-not $missing) { exit 0 }
$missing
exit 1
