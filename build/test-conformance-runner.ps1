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
Write-Output 'Conformance runner checks passed.'
