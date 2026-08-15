<#
.SYNOPSIS
Runs the cross-system tests: three clients, three API servers, one server.

.DESCRIPTION
A launcher, not a second implementation: the test itself lives in cross-system-test.sh and is handed
to the Bash that ships with Git for Windows. Keeping one copy of the logic is worth more than
avoiding the dependency, since the two would drift.

For every Minecraft version given, this stands up a real server pointed at Yggdrasil A, then joins
it with three separate clients — one on Mojang, one on A, one on B — and checks that profiles,
signing keys and texture domains follow the server while credentials do not, and that all of it is
handed back when the client leaves.

Tokens are passed through the environment rather than on the command line, so they do not show up in
a process listing. They are optional: without one, the check that a client's own token still reaches
its own API server is skipped and everything else runs.

JDK paths default to Adoptium's usual install locations and can be overridden with -Jdk8, -Jdk17,
-Jdk21 and -Jdk25, or by setting LOKI_JDK8 and friends beforehand.

.EXAMPLE
scripts\cross-system-test.ps1 -Stub
Runs the whole spread against two stub API servers, needing nothing of your own.

.EXAMPLE
scripts\cross-system-test.ps1 -YggdrasilA https://a.example/authlib-injector -YggdrasilB https://b.example/authlib-injector -TokenA $a -TokenB $b -ProbeUuid 2f4e...
The real thing.

.EXAMPLE
scripts\cross-system-test.ps1 -YggdrasilA ... -YggdrasilB ... -Versions 1.20.6
One version, when you are iterating.
#>
# PositionalBinding is off so that a bare version number cannot be taken for a parameter value
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Versions,

    [string] $YggdrasilA,
    [string] $YggdrasilB,
    [string] $TokenA,
    [string] $TokenB,
    [string] $TokenMojang,

    # A player that exists on Yggdrasil A. Without one there is nothing to look up, and the profile
    # and signature checks are skipped.
    [string] $ProbeUuid,

    # Stand up two stub API servers instead of using real ones
    [switch] $Stub,

    [string] $Jdk8,
    [string] $Jdk17,
    [string] $Jdk21,
    [string] $Jdk25
)

$ErrorActionPreference = 'Stop'

if (-not $Stub -and (-not $YggdrasilA -or -not $YggdrasilB)) {
    throw "Give -YggdrasilA and -YggdrasilB, or -Stub to run against stand-ins."
}

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

$majors = @(8, 11, 16, 17, 21, 25)
$overrides = @{ 8 = $Jdk8; 17 = $Jdk17; 21 = $Jdk21; 25 = $Jdk25 }

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

$env:LOKI_A_ROOT = $YggdrasilA
$env:LOKI_B_ROOT = $YggdrasilB
$env:LOKI_A_TOKEN = $TokenA
$env:LOKI_B_TOKEN = $TokenB
$env:LOKI_MOJANG_TOKEN = $TokenMojang
$env:LOKI_A_UUID = $ProbeUuid
if ($Stub) { $env:LOKI_STUB = "1" } else { $env:LOKI_STUB = "" }

# The four eras, same spread as the server tests: Netty relocated, services key info, fetched key
# set, endpoint discovery
if (-not $Versions) {
    $Versions = @("1.7.10", "1.12.2", "1.18.2", "1.19.4", "1.20.6", "1.21.1", "26.2", "26.3-snapshot-8")
}

$test = ConvertTo-BashPath (Join-Path $scripts 'cross-system-test.sh')
$passed = @()
$failed = @()
$skipped = @()

try {
    foreach ($version in $Versions) {
        & $bash $test $version
        switch ($LASTEXITCODE) {
            0 { $passed += $version }
            3 { $skipped += $version }
            default { $failed += $version }
        }
        # One agent build for the whole run: it is version independent, so rebuilding per version
        # only buys the chance of failing halfway through
        $env:LOKI_TEST_SKIP_BUILD = "1"
        Write-Host ""
    }
} finally {
    # Not left lying around in this session for whatever runs next
    foreach ($name in @('LOKI_A_TOKEN', 'LOKI_B_TOKEN', 'LOKI_MOJANG_TOKEN')) {
        Set-Item "env:$name" ""
    }
    $env:LOKI_TEST_SKIP_BUILD = ""
}

Write-Host "================================"
if ($passed) { Write-Host "passed: $($passed -join ' ')" }
if ($skipped) { Write-Host "skipped: $($skipped -join ' ')" }
if ($failed) {
    Write-Host "FAILED: $($failed -join ' ')"
    exit 1
}
Write-Host "cross-system-test: PASSED"
