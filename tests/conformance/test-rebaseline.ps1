<#
.SYNOPSIS
Verifies the deliberate-rebaseline audit at its public script seam using disposable manifest copies.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$audit = Join-Path $repositoryRoot 'build/verify-conformance-rebaseline.ps1'
$scratch = Join-Path ([IO.Path]::GetTempPath()) ('jbsa-rebaseline-' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $scratch
$baseline = Join-Path $scratch 'baseline.json'
$candidate = Join-Path $repositoryRoot 'tests/conformance/catalog.json'
$records = Join-Path $scratch 'records'
$null = New-Item -ItemType Directory -Path $records
$recordPath = Join-Path $records 'test-only-review.json'

<#
.SYNOPSIS
Requires rejection of an altered historical manifest against the committed candidate.
#>
function Assert-RebaselineRejected {
    param([string] $Name, [scriptblock] $Mutation)
    $copy = Get-Content -LiteralPath $candidate -Raw | ConvertFrom-Json -Depth 100
    & $Mutation $copy
    $copy | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $baseline -Encoding utf8
    $rejected = $false
    try { & $audit -BaselineCatalog $baseline -CandidateCatalog $candidate -RecordsDirectory $records }
    catch { $rejected = $true }
    if (-not $rejected) { throw "Rebaseline audit accepted: $Name" }
    Write-Host "PASS: $Name"
}

try {
    '{"schema_version":1,"contract":"conformance-v1","cases":[]}' | Set-Content -LiteralPath $baseline -Encoding utf8
    & $audit -BaselineCatalog $baseline -CandidateCatalog $candidate -RecordsDirectory $records
    Copy-Item -LiteralPath $candidate -Destination $baseline
    & $audit -BaselineCatalog $baseline -CandidateCatalog $candidate -RecordsDirectory $records
    Assert-RebaselineRejected 'identity mutation' { param($c) $c.cases[0].identity.operation = 'changed' }
    Assert-RebaselineRejected 'fixture digest mutation' { param($c) $c.tokens.fixture[0].sha256 = '0' * 64 }
    Assert-RebaselineRejected 'configuration digest mutation' { param($c) $c.tokens.configuration[0].sha256 = '0' * 64 }
    Assert-RebaselineRejected 'golden replacement without approval' {
        param($c)
        ($c.cases | Where-Object { $_.metadata.golden_bindings.Count -gt 0 } | Select-Object -First 1).metadata.golden_bindings[0].sha256 = '0' * 64
    }
    Assert-RebaselineRejected 'specification change retains stale golden' { param($c) $c.specification_set[0].sha256 = '0' * 64 }
    Assert-RebaselineRejected 'generator change retains stale golden' {
        param($c)
        ($c.cases | Where-Object { $_.metadata.golden_bindings.Count -gt 0 } | Select-Object -First 1).metadata.fixture_binding.generator.version = 'old'
    }
    Assert-RebaselineRejected 'new case cannot introduce unreviewed golden bytes' {
        param($c) $c.cases = @($c.cases | Where-Object { $_.metadata.golden_bindings.Count -eq 0 })
    }
    $copy = Get-Content -LiteralPath $candidate -Raw | ConvertFrom-Json -Depth 100
    $case = $copy.cases | Where-Object { $_.metadata.golden_bindings.Count -gt 0 } | Select-Object -First 1
    $newDigest = $case.metadata.golden_bindings[0].sha256
    $case.metadata.golden_bindings[0].sha256 = '0' * 64
    $copy | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $baseline -Encoding utf8
    $fixture = $case.metadata.fixture_binding
    $configurationToken = $copy.tokens.configuration | Where-Object { $_.token -ceq $case.identity.configuration }
    $configuration = Get-Content -LiteralPath (Join-Path $repositoryRoot $configurationToken.path) -Raw | ConvertFrom-Json -Depth 100
    $provenance = Get-Content -LiteralPath (Join-Path $repositoryRoot $fixture.provenance.manifest.path) -Raw | ConvertFrom-Json -Depth 100
    $golden = $provenance.goldens | Where-Object { $_.sha256 -ceq $newDigest }
    $record = [ordered]@{
        schema_version = 1
        golden_id = $golden.id
        status = 'approved'
        old_sha256 = '0' * 64
        new_sha256 = $newDigest
        source_fixture_sha256s = @($fixture.files.sha256)
        oracle_sha256 = $fixture.provenance.oracle_sha256
        generator = @{ id = $fixture.generator.id; version = $fixture.generator.version }
        configuration = @{
            case_configuration = $configuration
            generator_configuration = $fixture.generator.configuration
            specification_set = $copy.specification_set
        }
        affected_case_ids = @($case.identity.case_id)
        rationale = 'Disposable test record; this does not approve repository evidence.'
        semantic_difference = 'Test-only historical digest replaced by the existing committed object.'
        approval = @{ approver = 'test-only-maintainer'; approved_at = '2026-09-05T00:00:00Z'; decision = 'approved' }
    }
    $record | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $recordPath -Encoding utf8
    & $audit -BaselineCatalog $baseline -CandidateCatalog $candidate -RecordsDirectory $records
    Write-Host 'PASS: exact approved replacement record'
    $record.configuration.case_configuration.DdsTarget = 'changed'
    $record | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $recordPath -Encoding utf8
    Assert-RebaselineRejected 'approval with wrong full configuration' {
        param($c)
        ($c.cases | Where-Object { $_.metadata.golden_bindings.Count -gt 0 } | Select-Object -First 1).metadata.golden_bindings[0].sha256 = '0' * 64
    }
    Write-Host 'PASS: rebaseline audit checks'
}
finally {
    # The scratch directory is newly allocated by this test; delete only its individually known files.
    if (Test-Path -LiteralPath $baseline) { Remove-Item -LiteralPath $baseline }
    if (Test-Path -LiteralPath $recordPath) { Remove-Item -LiteralPath $recordPath }
    Remove-Item -LiteralPath $records
    Remove-Item -LiteralPath $scratch
}
