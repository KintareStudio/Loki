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

# Every major a Minecraft release has ever asked for, so a new one only needs adding here
$majors = @(8, 11, 16, 17, 21, 25)
$overrides = @{ 8 = $Jdk8; 17 = $Jdk17; 21 = $Jdk21 }

foreach ($major in $majors) {
    $path = $overrides[$major]
    if (-not $path) { $path = [Environment]::GetEnvironmentVariable("LOKI_JDK$major") }
    if (-not $path) { $path = Find-Jdk $major }

    if ($path) {
        Write-Host ("  JDK {0,-3} {1}" -f $major, $path)
        Set-Item "env:LOKI_JDK$major" (ConvertTo-BashPath $path)
    } else {
        Write-Host ("  JDK {0,-3} not found, versions needing it will be skipped" -f $major)
    }
}
Write-Host ""

$matrix = ConvertTo-BashPath (Join-Path $scripts 'real-server-matrix.sh')
& $bash $matrix @Versions
exit $LASTEXITCODE
