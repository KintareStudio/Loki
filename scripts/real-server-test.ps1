<#
.SYNOPSIS
Runs the real server tests from PowerShell.

.DESCRIPTION
A launcher, not a second implementation: the tests themselves live in real-server-test.sh and
real-server-matrix.sh, and this hands them to the Bash that ships with Git for Windows. Keeping one
copy of the logic is worth more than avoiding the dependency, since the two would drift.

JDK paths default to Adoptium's usual install locations and can be overridden with -Jdk8, -Jdk17
and -Jdk21, or by setting LOKI_JDK8 and friends beforehand.

.EXAMPLE
scripts\real-server-test.ps1 1.7.10
Runs one version.

.EXAMPLE
scripts\real-server-test.ps1
Runs the whole matrix: 1.7 through the current snapshots.
#>
# PositionalBinding is off so that a bare version number cannot be taken for a JDK path
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Versions,

    [string] $Jdk8,
    [string] $Jdk17,
    [string] $Jdk21
)

$ErrorActionPreference = 'Stop'

function Find-Bash {
    $candidates = @(
        "$env:ProgramFiles\Git\bin\bash.exe",
        "${env:ProgramFiles(x86)}\Git\bin\bash.exe",
        "$env:LOCALAPPDATA\Programs\Git\bin\bash.exe"
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    $onPath = Get-Command bash.exe -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    throw "Could not find Git Bash. Install Git for Windows, or pass its bash.exe on PATH."
}

# Newest first, so a machine with several picks the one the tests were written against
function Find-Jdk([string] $majorVersion) {
    $roots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft")
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        $match = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match "jdk-?$majorVersion(\.|-|$)" } |
            Sort-Object Name -Descending |
            Select-Object -First 1
        if ($match) { return $match.FullName }
    }
    return $null
}

# A Windows path Git Bash will understand: C:\x\y becomes /c/x/y
function ConvertTo-BashPath([string] $path) {
    if (-not $path) { return "" }
    $full = (Resolve-Path $path).Path
    return '/' + $full.Substring(0, 1).ToLower() + $full.Substring(2).Replace('\', '/')
}

$bash = Find-Bash
$scripts = Split-Path -Parent $PSCommandPath

if (-not $Jdk8) { $Jdk8 = $env:LOKI_JDK8 }
if (-not $Jdk17) { $Jdk17 = $env:LOKI_JDK17 }
if (-not $Jdk21) { $Jdk21 = $env:LOKI_JDK21 }
if (-not $Jdk8) { $Jdk8 = Find-Jdk 8 }
if (-not $Jdk17) { $Jdk17 = Find-Jdk 17 }
if (-not $Jdk21) { $Jdk21 = Find-Jdk 21 }

foreach ($jdk in @(@{ n = 'JDK 8'; v = $Jdk8 }, @{ n = 'JDK 17'; v = $Jdk17 }, @{ n = 'JDK 21'; v = $Jdk21 })) {
    if ($jdk.v) { Write-Host ("  {0,-7} {1}" -f $jdk.n, $jdk.v) }
    else { Write-Host ("  {0,-7} not found, versions needing it will be skipped" -f $jdk.n) }
}
Write-Host ""

$env:LOKI_JDK8 = ConvertTo-BashPath $Jdk8
$env:LOKI_JDK17 = ConvertTo-BashPath $Jdk17
$env:LOKI_JDK21 = ConvertTo-BashPath $Jdk21

$matrix = ConvertTo-BashPath (Join-Path $scripts 'real-server-matrix.sh')
& $bash $matrix @Versions
exit $LASTEXITCODE
