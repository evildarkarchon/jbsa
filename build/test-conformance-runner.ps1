<#
.SYNOPSIS
Exercises the public Conformance Case runner and verifies that missing product evidence fails closed.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output = Join-Path $root ('target/conformance-runner-test-' + [guid]::NewGuid().ToString('N'))
$runner = Join-Path $PSScriptRoot 'run-conformance.ps1'
& pwsh -NoLogo -NoProfile -NonInteractive -File $runner -RepositoryRoot $root -OutputDirectory $output
if ($LASTEXITCODE -ne 1) { throw 'Incomplete product evidence must return exit code 1.' }
$report = Get-Content -Raw -LiteralPath (Join-Path $output 'report.json') | ConvertFrom-Json -Depth 100
$catalog = Get-Content -Raw -LiteralPath (Join-Path $root 'tests/conformance/catalog.json') | ConvertFrom-Json -Depth 100
if ($report.automated_conformance -ne $false) { throw 'Harness readiness cannot establish Automated Conformance.' }
if ($report.results.Count -ne $catalog.cases.Count) { throw 'Every CV1 case must receive its own result.' }
foreach ($case in $report.results) {
    if ($case.result -ne 'INVALID') { throw "Unimplemented product case must be INVALID: $($case.case_id)" }
    if ($case.assertions.Count -eq 0) { throw 'Missing ordered case assertions.' }
}
$matrix = Get-Content -Raw -LiteralPath (Join-Path $output 'matrix.json')
$second = $output + '-repeat'
& pwsh -NoLogo -NoProfile -NonInteractive -File $runner -RepositoryRoot $root -OutputDirectory $second
if ($LASTEXITCODE -ne 1) { throw 'Repeated incomplete run unexpectedly succeeded.' }
if ($matrix -cne (Get-Content -Raw -LiteralPath (Join-Path $second 'matrix.json'))) {
    throw 'Matrix bytes must not depend on timestamps, duration, or output directory.'
}
$libraryDirectory = if (Test-Path -LiteralPath (Join-Path $root 'jbsa/target/libs')) { 'jbsa/target/libs' } else { 'jbsa/target' }
$cliDirectory = if (Test-Path -LiteralPath (Join-Path $root 'jbsa-cli/target/libs')) { 'jbsa-cli/target/libs' } else { 'jbsa-cli/target' }
$libraryJar = @(Get-ChildItem -LiteralPath (Join-Path $root $libraryDirectory) -Filter 'jbsa-*.jar' |
    Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
$cliJar = @(Get-ChildItem -LiteralPath (Join-Path $root $cliDirectory) -Filter 'jbsa-cli-*.jar')
if ($libraryJar.Count -ne 1 -or $cliJar.Count -ne 1) { throw 'The Gradle candidate-path regression needs one library and CLI JAR.' }
$exact = $output + '-exact-candidates'
& pwsh -NoLogo -NoProfile -NonInteractive -File $runner -RepositoryRoot $root -OutputDirectory $exact `
    -LibraryArtifactPath $libraryJar[0].FullName -CliArtifactPath $cliJar[0].FullName `
    -CodecProfilePath (Join-Path $root 'jbsa/src/main/resources/META-INF/jbsa-codec-profile.json')
if ($LASTEXITCODE -ne 1) { throw 'Incomplete exact-candidate evidence must retain exit code 1.' }
$exactReport = Get-Content -Raw -LiteralPath (Join-Path $exact 'report.json') | ConvertFrom-Json -Depth 100
$candidatePaths = @($exactReport.results[0].candidate_artifacts.path)
$expectedLibrary = $libraryDirectory + '/' + $libraryJar[0].Name
$expectedCli = $cliDirectory + '/' + $cliJar[0].Name
if ($candidatePaths -cnotcontains $expectedLibrary -or $candidatePaths -cnotcontains $expectedCli) {
    throw 'The Gradle candidates were not bound into every Conformance Case result.'
}
if (-not $exactReport.results[0].codec_profile_sha256) { throw 'The exact codec profile identity was omitted.' }
Write-Output 'Conformance runner checks passed.'
