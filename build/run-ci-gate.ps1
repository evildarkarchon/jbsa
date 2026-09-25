<#
.SYNOPSIS
Runs one deterministic JBSA build gate through the authoritative Gradle implementation.

.PARAMETER Gate
The compile, unit, architecture, formatting, policy, or Assurance v2 conformance gate to run.

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
    'conformance' { @('--no-daemon', ':jbsa-conformance-tests:automatedAssurance') }
}

Push-Location $reactorRoot
try {
    if ($Gate -eq 'conformance') {
        $selection = Join-Path $reactorRoot 'target/assurance/selection.json'
        $tier = if ($env:GITHUB_EVENT_NAME -ceq 'pull_request') { 'affected' } else { 'full' }
        $selectionArguments = @(
            'build/assurance/plan.py',
            'select',
            'tests/assurance/plan.json',
            '--output',
            $selection,
            '--tier',
            $tier,
            '--environment',
            'hosted'
        )
        if ($tier -ceq 'affected') {
            if ($env:JBSA_BASE_REVISION -cnotmatch '^[0-9a-f]{40}$') {
                throw 'Pull-request assurance selection requires an exact base revision.'
            }
            $changedPaths = @(git diff --name-only "$($env:JBSA_BASE_REVISION)...HEAD")
            if ($LASTEXITCODE -ne 0) {
                throw 'Unable to determine Assurance v2 impact from the pull-request base.'
            }
            # A large PR can exceed Windows process argument limits when each path is passed separately.
            $changedFile = Join-Path $reactorRoot 'target/assurance/changed-paths.json'
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($changedFile)) | Out-Null
            $changedJson = ConvertTo-Json -InputObject $changedPaths -Compress
            [IO.File]::WriteAllText($changedFile, "$changedJson`n", [Text.UTF8Encoding]::new($false))
            $selectionArguments += @('--changed-file', $changedFile)
        }
        & python @selectionArguments
        if ($LASTEXITCODE -ne 0) {
            throw 'Assurance v2 impact selection failed.'
        }
        $gradleArguments += "-PjbsaAssuranceSelection=$selection"
    }

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
