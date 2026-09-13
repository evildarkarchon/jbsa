<#
.SYNOPSIS
Runs one deterministic JBSA build gate through its migration-stage build implementation.

.PARAMETER Gate
The compile, unit, architecture, formatting, policy, or conformance harness gate to run.

.NOTES
These gates produce hosted build evidence only. They do not perform or claim Release Qualification.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('compile', 'unit', 'architecture', 'formatting', 'policy', 'conformance')]
    [string] $Gate
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$reactorRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gradleWrapper = Join-Path $reactorRoot $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })

$gradleArguments = switch ($Gate) {
    'compile' {
        @('--no-daemon', ':jbsa:classes', ':jbsa-cli:classes', ':jbsa-test-support:classes', ':jbsa-conformance-tests:classes', ':jbsa-benchmarks:classes')
    }
    'unit' {
        @('--no-daemon', ':jbsa:test', ':jbsa-cli:test', ':jbsa-test-support:test', ':jbsa-conformance-tests:test', ':jbsa-benchmarks:test')
    }
    'architecture' { @('--no-daemon', ':jbsa-conformance-tests:architectureTest') }
    'formatting' { @('--no-daemon', 'spotlessCheck') }
    'policy' { @('--no-daemon', ':jbsa-conformance-tests:buildPolicyTest') }
    'conformance' { @('--no-daemon', ':jbsa-conformance-tests:automatedConformance') }
}

Push-Location $reactorRoot
try {
    & $gradleWrapper @gradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "The $Gate Gradle gate failed with exit code $LASTEXITCODE."
    }

    if ($Gate -eq 'policy') {
        & (Join-Path $PSScriptRoot 'verify-reproducible-build.ps1')
        if (-not $?) {
            throw 'The two-build reproducibility check failed.'
        }
    }
}
finally {
    Pop-Location
}
