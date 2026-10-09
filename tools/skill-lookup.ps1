<#
.SYNOPSIS
    Looks skills up in the game data by name, group, id or class, and prints what the bot code asks of them.

.DESCRIPTION
    Made for turning a class guide written with the client's names into rules written with the server's. Every skill has its own id per level and a name
    that says little, so the rules key on the skill's group; this finds the group, and the facts that decide how a bot may use the skill, without opening
    a 35 MB XML file by hand:

      id, level (from the class tree), name, group, cooldown in seconds, activation (ACTIVE, TOGGLE, MAINTAIN, passive), sub type,
      who it is aimed at (relation, type, first target) and how far it reaches

    With -Class the class's skill tree is read as well, which is where the level and the stigma tier (1 regular, 2 and 3 advanced, 4 linked) live.

.PARAMETER Name
    A regular expression matched against the skill's name, case insensitive.

.PARAMETER Group
    A regular expression matched against the skill's group.

.PARAMETER Id
    One skill id.

.PARAMETER Class
    A class id as the skill tree spells it (TEMPLAR, CLERIC, GLADIATOR...). Only that class's skills are listed, with their level.

.PARAMETER MaxLevel
    With -Class: skills learned above this level are left out.

.PARAMETER DataRoot
    The game-server folder. Defaults to the one beside this script.

.EXAMPLE
    .\tools\skill-lookup.ps1 -Name 'provoking roar'
    .\tools\skill-lookup.ps1 -Class TEMPLAR -MaxLevel 30
    .\tools\skill-lookup.ps1 -Group 'KN_STONEBODY|KN_IRONBODY'
#>
param(
    [string]$Name,
    [string]$Group,
    [int]$Id,
    [string]$Class,
    [int]$MaxLevel = 0,
    [string]$DataRoot = (Join-Path (Split-Path -Parent $PSScriptRoot) 'game-server')
)

$ErrorActionPreference = 'Stop'

if (-not ($Name -or $Group -or $Id -or $Class)) {
    throw 'Give at least one of -Name, -Group, -Id or -Class.'
}

$templates = Join-Path $DataRoot 'data\static_data\skills\skill_templates.xml'
$tree = Join-Path $DataRoot 'data\static_data\skill_tree\skill_tree.xml'
foreach ($file in $templates, $tree) {
    if (-not (Test-Path $file)) {
        throw "Not found: $file"
    }
}

function Get-Attribute([string]$Line, [string]$Attribute) {
    if ($Line -match "(?:^|\s)$Attribute=`"([^`"]*)`"") { return $Matches[1] }
    return $null
}

# class tree: skill id -> level and stigma tier, for the class asked about, whichever race it is written under
$learned = @{}
if ($Class) {
    foreach ($line in Get-Content $tree) {
        if ($line -notmatch "classId=`"$Class`"") { continue }
        $skillId = Get-Attribute $line 'skillId'
        if (-not $skillId) { continue }
        $level = [int](Get-Attribute $line 'minLevel')
        if (-not $learned.ContainsKey($skillId) -or $learned[$skillId].Level -gt $level) {
            $learned[$skillId] = [pscustomobject]@{ Level = $level; Stigma = (Get-Attribute $line 'stigma') }
        }
    }
    if ($learned.Count -eq 0) {
        throw "No skills for class $Class in the skill tree. Spell it as the tree does, for example TEMPLAR."
    }
}

$results = New-Object System.Collections.Generic.List[object]
$pending = $null
$reader = [System.IO.StreamReader]::new($templates)
try {
    while ($null -ne ($line = $reader.ReadLine())) {
        if ($line -match '<skill_template ') {
            if ($pending) { $results.Add($pending) }
            $pending = $null
            $skillId = Get-Attribute $line 'skill_id'
            $skillName = Get-Attribute $line 'name'
            $skillGroup = Get-Attribute $line 'group'
            if ($Id -and [int]$skillId -ne $Id) { continue }
            if ($Name -and $skillName -notmatch $Name) { continue }
            if ($Group -and ($skillGroup -notmatch $Group)) { continue }
            if ($Class -and -not $learned.ContainsKey($skillId)) { continue }
            if ($Class -and $MaxLevel -gt 0 -and $learned[$skillId].Level -gt $MaxLevel) { continue }
            $cooldown = Get-Attribute $line 'cooldown'
            $pending = [pscustomobject]@{
                Id         = [int]$skillId
                Level      = if ($Class) { $learned[$skillId].Level } else { $null }
                Stigma     = if ($Class) { $learned[$skillId].Stigma } else { $null }
                Name       = $skillName
                Group      = $skillGroup
                Cooldown_s = if ($cooldown) { [int]$cooldown / 10 } else { 0 }
                Activation = Get-Attribute $line 'activation'
                SubType    = Get-Attribute $line 'skillsubtype'
                Aimed      = $null
                Reach_m    = $null
            }
        } elseif ($pending -and -not $pending.Aimed -and $line -match '<properties ') {
            $relation = Get-Attribute $line 'target_relation'
            $type = Get-Attribute $line 'target_type'
            $first = Get-Attribute $line 'first_target'
            $pending.Aimed = "$relation/$type/$first"
            $pending.Reach_m = Get-Attribute $line 'first_target_range'
            if ((Get-Attribute $line 'awr') -eq 'true') { $pending.Reach_m = "$($pending.Reach_m)+weapon" }
        }
    }
    if ($pending) { $results.Add($pending) }
} finally {
    $reader.Dispose()
}

if ($results.Count -eq 0) {
    Write-Host 'No skill matches.' -ForegroundColor Yellow
    exit 1
}
$results | Sort-Object @{ Expression = { if ($null -eq $_.Level) { 0 } else { $_.Level } } }, Id
Write-Host "$($results.Count) skill(s)." -ForegroundColor DarkGray
