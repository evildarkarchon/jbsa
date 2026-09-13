<#
.SYNOPSIS
Exercises the public CI-gate launcher against an isolated Gradle-wrapper fixture.

.NOTES
The fixture records the exact launcher arguments so this test remains independent of Gradle task
implementation while preserving the public gate-name contract.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "jbsa-ci-gate-test-$([guid]::NewGuid().ToString('N'))"
$priorLog = $env:JBSA_CI_GATE_TEST_LOG
$priorFailure = $env:JBSA_CI_GATE_TEST_FAILURE

try {
    $fixtureBuild = Join-Path $fixtureRoot 'build'
    New-Item -ItemType Directory -Path $fixtureBuild -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'run-ci-gate.ps1') -Destination $fixtureBuild

    $fixtureGradle = @'
$record = [ordered]@{ arguments = @($args) } | ConvertTo-Json -Compress
Add-Content -LiteralPath $env:JBSA_CI_GATE_TEST_LOG -Value $record
if ($env:JBSA_CI_GATE_TEST_FAILURE -eq 'true') { exit 17 }
'@
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'fixture-gradle.ps1') -Value $fixtureGradle
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'gradlew.bat') -Value '@pwsh -NoLogo -NoProfile -NonInteractive -File "%~dp0fixture-gradle.ps1" %*'
    $posixWrapper = @'
#!/usr/bin/env pwsh
& "$PSScriptRoot/fixture-gradle.ps1" @args
exit $LASTEXITCODE
'@
    $posixPath = Join-Path $fixtureRoot 'gradlew'
    Set-Content -LiteralPath $posixPath -Value $posixWrapper -NoNewline
    if (-not $IsWindows) {
        & chmod +x $posixPath
        if ($LASTEXITCODE -ne 0) { throw 'Could not mark the fixture Gradle launcher executable.' }
    }

    $reproducibilityFixture = @'
Add-Content -LiteralPath $env:JBSA_CI_GATE_TEST_LOG -Value 'REPRODUCIBILITY'
'@
    Set-Content -LiteralPath (Join-Path $fixtureBuild 'verify-reproducible-build.ps1') -Value $reproducibilityFixture

    $env:JBSA_CI_GATE_TEST_LOG = Join-Path $fixtureRoot 'invocations.log'
    $runner = Join-Path $fixtureBuild 'run-ci-gate.ps1'
    $expectedMappings = [ordered]@{
        compile = @('--no-daemon', ':jbsa:classes', ':jbsa-cli:classes', ':jbsa-test-support:classes', ':jbsa-conformance-tests:classes', ':jbsa-benchmarks:classes')
        unit = @('--no-daemon', ':jbsa:test', ':jbsa-cli:test', ':jbsa-test-support:test', ':jbsa-conformance-tests:test', ':jbsa-benchmarks:test')
        architecture = @('--no-daemon', ':jbsa-conformance-tests:architectureTest')
        formatting = @('--no-daemon', 'spotlessCheck')
        policy = @('--no-daemon', ':jbsa-conformance-tests:buildPolicyTest')
        conformance = @('--no-daemon', ':jbsa-conformance-tests:automatedConformance')
    }

    foreach ($entry in $expectedMappings.GetEnumerator()) {
        Remove-Item -LiteralPath $env:JBSA_CI_GATE_TEST_LOG -ErrorAction SilentlyContinue
        $env:JBSA_CI_GATE_TEST_FAILURE = 'false'
        $output = & pwsh -NoLogo -NoProfile -NonInteractive -File $runner -Gate $entry.Key 2>&1
        if ($LASTEXITCODE -ne 0) { throw "The $($entry.Key) fixture failed: $output" }
        $records = @(Get-Content -LiteralPath $env:JBSA_CI_GATE_TEST_LOG)
        $actual = @(($records[0] | ConvertFrom-Json).arguments)
        if (($actual -join "`n") -cne (@($entry.Value) -join "`n")) {
            throw "The $($entry.Key) gate mapped to '$($actual -join ' ')' instead of '$($entry.Value -join ' ')'."
        }
        $expectedRecordCount = if ($entry.Key -eq 'policy') { 2 } else { 1 }
        if ($records.Count -ne $expectedRecordCount) {
            throw "The $($entry.Key) gate produced $($records.Count) fixture actions instead of $expectedRecordCount."
        }
        if ($entry.Key -eq 'policy' -and $records[1] -cne 'REPRODUCIBILITY') {
            throw 'The policy gate did not retain the two-build reproducibility procedure.'
        }
    }

    Remove-Item -LiteralPath $env:JBSA_CI_GATE_TEST_LOG -ErrorAction SilentlyContinue
    $env:JBSA_CI_GATE_TEST_FAILURE = 'true'
    $failure = & pwsh -NoLogo -NoProfile -NonInteractive -File $runner -Gate policy 2>&1
    if ($LASTEXITCODE -eq 0 -or ($failure -join "`n") -notmatch 'policy Gradle gate failed with exit code 17') {
        throw 'The launcher did not propagate the exact Gradle gate failure.'
    }
    $failureRecords = @(Get-Content -LiteralPath $env:JBSA_CI_GATE_TEST_LOG)
    if ($failureRecords.Count -ne 1) {
        throw 'The policy gate continued into reproducibility after its Gradle task failed.'
    }

    Write-Output 'CI gate launcher checks passed.'
}
finally {
    $env:JBSA_CI_GATE_TEST_LOG = $priorLog
    $env:JBSA_CI_GATE_TEST_FAILURE = $priorFailure
    $resolvedRoot = [IO.Path]::GetFullPath($fixtureRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing cleanup outside the temporary directory: $resolvedRoot"
    }
    if (Test-Path -LiteralPath $resolvedRoot) { Remove-Item -LiteralPath $resolvedRoot -Recurse -Force }
}
