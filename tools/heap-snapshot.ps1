<#
.SYNOPSIS
  Writes the game server's memory and CPU against the bots awake: cores busy over a minute, heap before and after a full GC, the top of the class
  histogram, and the last review of the director. The full GCs pause the server one to two seconds each, so not inside a window being measured for
  something else.
.EXAMPLE
  .\tools\heap-snapshot.ps1 -Label fixed-25
#>
param(
	[string]$Label = 'snapshot',
	[int]$Top = 25,
	[string]$ServerRoot = $env:AION_SERVER_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $ServerRoot) { $ServerRoot = 'D:\Jeux\Aion\server' }

$process = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object CommandLine -match 'GameServer' | Select-Object -First 1
if (-not $process) { throw 'No game server running.' }
$gamePid = $process.ProcessId

$log = Join-Path $ServerRoot 'game-server\log\server_console.log'
$review = @(Select-String -Path $log -Pattern 'BotDirector - (Population review|  map )' | Select-Object -Last 60 | ForEach-Object Line)
$maps = @{}
$lastSummary = ''
foreach ($line in $review) {
	if ($line -match 'Population review') { $lastSummary = $line; $maps = @{} }
	elseif ($line -match 'map (\d+): (\d+) awake of (\d+), wants (\d+)') { $maps[$Matches[1]] = [int[]]($Matches[2], $Matches[4]) }
}
$awake = ($maps.Values | ForEach-Object { $_[0] } | Measure-Object -Sum).Sum
$wanted = ($maps.Values | ForEach-Object { $_[1] } | Measure-Object -Sum).Sum

$out = Join-Path $ServerRoot 'game-server\log\bot-measures'
New-Item -ItemType Directory -Force $out | Out-Null
$file = Join-Path $out ("heap-{0}-{1}.txt" -f $Label, (Get-Date -Format 'yyyy-MM-dd-HHmm'))

# Cores in use over a minute, before anything here pauses the server.
$cpuBefore = (Get-Process -Id $gamePid).CPU
Start-Sleep -Seconds 60
$cores = [math]::Round(((Get-Process -Id $gamePid).CPU - $cpuBefore) / 60, 2)

$before = & jcmd $gamePid GC.heap_info | Out-String
$histogram = & jcmd $gamePid GC.class_histogram
# The histogram's own collection is followed by allocations while it prints; collect again and read at once, then leave the young regions out.
& jcmd $gamePid GC.run | Out-Null
$after = & jcmd $gamePid GC.heap_info | Out-String
$workingSet = [math]::Round((Get-Process -Id $gamePid).WorkingSet64 / 1MB)

$live = '?'
if ($after -match 'used (\d+)K[^\r\n]*\r?\n\s*region size (\d+)K, (\d+) young') {
	$live = [math]::Round(([long]$Matches[1] - [long]$Matches[2] * [long]$Matches[3]) / 1024)
}
$report = @(
	"$Label at $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), pid $gamePid"
	"bots awake $awake, wanted $wanted, over $($maps.Count) map(s)"
	"live set after a full GC, young regions left out: $live MB; process working set $workingSet MB; cores busy over a minute: $cores of $([Environment]::ProcessorCount)"
	"last review: $lastSummary"
	''
	'--- heap before the full GC'
	$before
	'--- heap after it'
	$after
	"--- class histogram, top $Top"
	($histogram | Select-Object -First ($Top + 3))
)
$report | Set-Content -Encoding utf8 $file
$report | Select-Object -First 3
"written to $file"
