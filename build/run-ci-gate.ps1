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
$mavenWrapper = Join-Path $reactorRoot 'mvnw.cmd'
$gradleWrapper = Join-Path $reactorRoot 'gradlew.bat'

if ($Gate -eq 'conformance') {
    Push-Location $reactorRoot
    try {
        & $gradleWrapper --no-daemon ':jbsa-conformance-tests:automatedConformance'
        if ($LASTEXITCODE -ne 0) {
            throw "The conformance Gradle gate failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
    return
}

$mavenArguments = switch ($Gate) {
    'compile' { @('-B', '-ntp', '-C', '-DskipTests', 'compile') }
    'unit' { @('-B', '-ntp', '-C', 'test') }
    'architecture' { @('-B', '-ntp', '-C', '-Dgroups=architecture', 'verify') }
    'formatting' { @('-B', '-ntp', '-C', 'spotless:check') }
    'policy' { @('-B', '-ntp', '-C', '-Dgroups=build-policy', 'verify') }
    'conformance' { throw 'The conformance gate must use the Gradle workflow.' }
}

Push-Location $reactorRoot
try {
    & $mavenWrapper @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "The $Gate Maven gate failed with exit code $LASTEXITCODE."
    }

    if ($Gate -eq 'policy') {
        & $mavenWrapper -B -ntp -C org.apache.maven.plugins:maven-artifact-plugin:3.6.1:check-buildplan '-Dcheck.buildplan.tasks=verify'
        if ($LASTEXITCODE -ne 0) {
            throw "The reproducible-build plan check failed with exit code $LASTEXITCODE."
        }

        & (Join-Path $PSScriptRoot 'verify-reproducible-build.ps1')
        if (-not $?) {
            throw 'The two-build reproducibility check failed.'
        }
    }
}
finally {
    Pop-Location
}
