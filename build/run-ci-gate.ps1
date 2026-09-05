<#
.SYNOPSIS
Runs one deterministic JBSA build gate with the checked-in Maven wrapper.

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

$mavenArguments = switch ($Gate) {
    'compile' { @('-B', '-ntp', '-C', '-DskipTests', 'compile') }
    'unit' { @('-B', '-ntp', '-C', 'test') }
    'architecture' { @('-B', '-ntp', '-C', '-Dgroups=architecture', 'verify') }
    'formatting' { @('-B', '-ntp', '-C', 'spotless:check') }
    'policy' { @('-B', '-ntp', '-C', '-Dgroups=build-policy', 'verify') }
    'conformance' { @('-B', '-ntp', '-C', '-Dgroups=conformance-harness', 'verify') }
}

Push-Location $reactorRoot
try {
    & $mavenWrapper @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "The $Gate Maven gate failed with exit code $LASTEXITCODE."
    }

    if ($Gate -eq 'conformance') {
        & pwsh -NoLogo -NoProfile -NonInteractive -File (Join-Path $PSScriptRoot 'run-conformance.ps1') -RepositoryRoot $reactorRoot -OutputDirectory 'target/conformance'
        # Issue #31 verifies the harness; #50 enables the product claim gate after the archive slices.
        # Exit 1 still publishes every blocked case, and never becomes an Automated Conformance claim.
        if ($LASTEXITCODE -notin @(0, 1)) { throw 'Conformance inventory or evidence reporting is invalid.' }
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
