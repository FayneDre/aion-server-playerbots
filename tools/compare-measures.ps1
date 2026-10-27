<#
.SYNOPSIS
    Puts measure.sh reports side by side, per half hour, and flags what moved beyond the noise of the reports taken before.

.DESCRIPTION
    A change is judged by whether its figures leave the band two measurements of the same code already disagree by. Read by eye, three reports of
    twenty counters each are sixty numbers, and a window that began late after a restart reads as an improvement: so every counter is brought to a
    rate per thirty minutes of the log the report covers (its first line says from when to when), the band is the lowest and highest of the -Before
    reports, widened by -Slack, and an -After figure outside it is marked.

    Written on 27 October 2026 for the module structure plan, whose every step must leave these figures where they were.

.PARAMETER Before
    The reports of the code as it was: two or more, so that the band is something measured.

.PARAMETER After
    The reports of the code changed.

.PARAMETER Slack
    How much wider than the measured band a figure may still be, as a share of the band's middle. 0.15 by default.

.EXAMPLE
    .\tools\compare-measures.ps1 -Before nav-2026-10-27-2015.txt,nav-2026-10-27-2045.txt -After nav-2026-10-27-2130.txt
#>
param(
    [Parameter(Mandatory)][string[]]$Before,
    [Parameter(Mandatory)][string[]]$After,
    [double]$Slack = 0.15,
    [string]$ServerRoot = $env:AION_SERVER_HOME
)

$ErrorActionPreference = 'Stop'
if (-not $ServerRoot) { $ServerRoot = [Environment]::GetEnvironmentVariable('AION_SERVER_HOME', 'User') }
$measures = if ($ServerRoot) { Join-Path $ServerRoot 'game-server\log\bot-measures' } else { '.' }

function Resolve-Report([string]$name) {
    if (Test-Path $name) { return (Resolve-Path $name).Path }
    $inMeasures = Join-Path $measures $name
    if (Test-Path $inMeasures) { return $inMeasures }
    throw "No report $name, here or in $measures"
}

# One report: its minutes, and every counter of its first block ("  name   number"), as a rate per thirty minutes
function Read-Report([string]$path) {
    $lines = Get-Content $path
    if ($lines[0] -notmatch 'from (\d+):(\d+):(\d+) to (\d+):(\d+):(\d+)') { throw "$path does not start with the window it covers" }
    $from = [int]$Matches[1] * 3600 + [int]$Matches[2] * 60 + [int]$Matches[3]
    $to = [int]$Matches[4] * 3600 + [int]$Matches[5] * 60 + [int]$Matches[6]
    if ($to -lt $from) { $to += 86400 }
    $minutes = [math]::Max(1.0, ($to - $from) / 60.0)
    $rates = [ordered]@{}
    foreach ($line in $lines | Select-Object -Skip 1) {
        if ($line -notmatch '^  (\S.*?)\s{2,}(\d+)$') { break }
        $rates[$Matches[1]] = [double]$Matches[2] * 30.0 / $minutes
    }
    [pscustomobject]@{ Name = Split-Path $path -Leaf; Minutes = $minutes; Rates = $rates }
}

# split here as well: run with -File, a list written a,b arrives as one string
$beforeReports = @($Before -split ',' | Where-Object { $_ } | ForEach-Object { Read-Report (Resolve-Report $_.Trim()) })
$afterReports = @($After -split ',' | Where-Object { $_ } | ForEach-Object { Read-Report (Resolve-Report $_.Trim()) })
if ($beforeReports.Count -lt 2) { Write-Host 'One report before gives no band to judge by: take a second.' -ForegroundColor Yellow }

$columns = @($beforeReports) + @($afterReports)
"{0,-28}" -f 'per 30 min' + (($columns | ForEach-Object { "{0,12}" -f ($_.Name -replace '^nav-\d{4}-\d{2}-|\.txt$', '') }) -join '') + '   band before'
"{0,-28}" -f 'minutes of log' + (($columns | ForEach-Object { "{0,12:0}" -f $_.Minutes }) -join '')
$moved = 0
foreach ($counter in $beforeReports[0].Rates.Keys) {
    $values = @($beforeReports | ForEach-Object { $_.Rates[$counter] })
    $low = ($values | Measure-Object -Minimum).Minimum
    $high = ($values | Measure-Object -Maximum).Maximum
    $margin = $Slack * [math]::Max(1.0, ($low + $high) / 2)
    $cells = foreach ($report in $columns) {
        $value = $report.Rates[$counter]
        $flag = ' '
        if ($afterReports -contains $report -and $null -ne $value) {
            if ($value -gt $high + $margin) { $flag = '+'; $moved++ }
            elseif ($value -lt $low - $margin) { $flag = '-'; $moved++ }
        }
        if ($null -eq $value) { "{0,12}" -f '?' } else { "{0,11:0}{1}" -f $value, $flag }
    }
    "{0,-28}" -f $counter + ($cells -join '') + ("   {0:0} to {1:0}" -f $low, $high)
}
''
if ($moved -eq 0) {
    Write-Host 'Every figure after is within the band of the reports before.' -ForegroundColor Green
} else {
    Write-Host "$moved figure(s) after outside the band before (+ above, - below): look at those first." -ForegroundColor Yellow
}
