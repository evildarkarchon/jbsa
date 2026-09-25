<#
.SYNOPSIS
Audits a candidate CV1 catalog against an explicit historical catalog without writing either.
.PARAMETER BaselineCatalog
Exact historical catalog JSON. A first catalog may use an explicitly supplied empty cases array.
.PARAMETER CandidateCatalog
Candidate catalog, validated against the current committed content bindings.
.PARAMETER RecordsDirectory
Directory of deliberately authored, maintainer-approved rebaseline JSON records.
.NOTES
Normal conformance execution does not invoke a rebaseline or approve expected output. This command
only verifies review records. A changed fixture/configuration token must receive a new identity.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $BaselineCatalog,
    [string] $CandidateCatalog = (Join-Path $PSScriptRoot '../tests/conformance/catalog.json'),
    [string] $RecordsDirectory = (Join-Path $PSScriptRoot '../tests/conformance/rebaselines')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'conformance-catalog.ps1')
$baseline = Read-ConformanceJson -Path $BaselineCatalog
$candidate = Read-ConformanceCatalog -Path $CandidateCatalog -RepositoryRoot $repositoryRoot
$baselineCases = Get-ConformanceProperty $baseline 'cases' 'Baseline catalog'
if ((Get-ConformanceProperty $baseline 'contract' 'Baseline catalog') -cne 'conformance-v1' -or
    (Get-ConformanceProperty $baseline 'schema_version' 'Baseline catalog') -ne 1) {
    throw 'Baseline must identify conformance-v1 explicitly.'
}

<#
.SYNOPSIS
Compares JSON values structurally, independent of member ordering while preserving array order.
#>
function Test-ConformanceJsonEqual {
    param([AllowNull()][object] $Left, [AllowNull()][object] $Right)
    if ($null -eq $Left -or $null -eq $Right) { return ($null -eq $Left -and $null -eq $Right) }
    if ($Left -is [pscustomobject] -and $Right -is [pscustomobject]) {
        $leftNames = @($Left.PSObject.Properties.Name | Sort-Object -CaseSensitive)
        $rightNames = @($Right.PSObject.Properties.Name | Sort-Object -CaseSensitive)
        if (($leftNames -join "`n") -cne ($rightNames -join "`n")) { return $false }
        foreach ($name in $leftNames) {
            if (-not (Test-ConformanceJsonEqual $Left.$name $Right.$name)) { return $false }
        }
        return $true
    }
    if ($Left -is [array] -and $Right -is [array]) {
        if ($Left.Count -ne $Right.Count) { return $false }
        for ($index = 0; $index -lt $Left.Count; $index++) {
            if (-not (Test-ConformanceJsonEqual $Left[$index] $Right[$index])) { return $false }
        }
        return $true
    }
    return ($Left.GetType() -eq $Right.GetType() -and $Left -ceq $Right)
}

<#
.SYNOPSIS
Builds an ordinal case index while rejecting duplicate or internally inconsistent historical keys.
#>
function Get-ConformanceHistoricalCases {
    param([object[]] $Cases)
    $index = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($case in $Cases) {
        $identity = $case.identity
        $expected = "CV1-$($identity.archive_family).$($identity.operation).$($identity.fixture).$($identity.codec).$($identity.configuration)"
        if (@($identity.PSObject.Properties).Count -ne 7 -or $identity.contract -cne 'conformance-v1' -or
            $identity.case_id -cne $expected -or $index.ContainsKey($expected)) {
            throw "Invalid or duplicate historical identity: '$($identity.case_id)'."
        }
        $index.Add($expected, $case)
    }
    return ,$index
}

<#
.SYNOPSIS
Finds approved reviews binding the case, golden, source provenance, and full candidate configuration.
.PARAMETER Records
Review records already validated against the rebaseline schema and explicit approval requirements.
.PARAMETER OldSha256
Required historical golden digest for a replacement; omit when a new case introduces golden bytes.
.OUTPUTS
Every matching review record. The caller must require exactly one match for its operation.
.NOTES
Reads digest-bound configuration and provenance documents; missing or mismatched content throws.
#>
function Find-ConformanceApprovedGoldenReview {
    param(
        [object] $Case,
        [string] $NewSha256,
        [object] $Catalog,
        [object[]] $Records,
        [string] $RepositoryRoot,
        [string] $OldSha256
    )
    $fixture = $Case.metadata.fixture_binding
    $configurationToken = $Catalog.tokens.configuration | Where-Object { $_.token -ceq $Case.identity.configuration }
    $configuration = Read-ConformanceJson -Path (Resolve-ConformanceBinding $configurationToken $RepositoryRoot)
    $fullConfiguration = [pscustomobject]@{
        case_configuration = $configuration
        generator_configuration = $fixture.generator.configuration
        specification_set = $Catalog.specification_set
    }
    $provenance = Read-ConformanceJson -Path (Resolve-ConformanceBinding $fixture.provenance.manifest $RepositoryRoot)
    $golden = @($provenance.goldens | Where-Object { $_.sha256 -ceq $NewSha256 })
    $requireHistoricalDigest = $PSBoundParameters.ContainsKey('OldSha256')
    return ($Records | Where-Object {
        (-not $requireHistoricalDigest -or $_.old_sha256 -ceq $OldSha256) -and
        $_.new_sha256 -ceq $NewSha256 -and $Case.identity.case_id -cin $_.affected_case_ids -and
        $golden.Count -eq 1 -and $_.golden_id -ceq $golden[0].id -and
        $_.generator.id -ceq $fixture.generator.id -and $_.generator.version -ceq $fixture.generator.version -and
        $_.oracle_sha256 -ceq $fixture.provenance.oracle_sha256 -and
        (Test-ConformanceJsonEqual @($_.source_fixture_sha256s | Sort-Object) @($fixture.files.sha256 | Sort-Object)) -and
        (Test-ConformanceJsonEqual $_.configuration $fullConfiguration)
    })
}

$oldCases = Get-ConformanceHistoricalCases -Cases @($baselineCases)
$newCases = Get-ConformanceHistoricalCases -Cases @($candidate.cases)
if ($oldCases.Count -eq 0) {
    Write-Host 'PASS: explicit first-catalog baseline; committed corpus provenance remains required.'
    return
}

foreach ($field in @('fixture', 'configuration')) {
    foreach ($oldToken in $baseline.tokens.$field) {
        $newToken = @($candidate.tokens.$field | Where-Object { $_.token -ceq $oldToken.token })
        if ($newToken.Count -gt 1 -or ($newToken.Count -eq 1 -and $oldToken.sha256 -cne $newToken[0].sha256)) {
            throw "Immutable $field token '$($oldToken.token)' changed its digest in place; create a new token and case identifier."
        }
    }
}

$records = @()
if (Test-Path -LiteralPath $RecordsDirectory -PathType Container) {
    $schema = Join-Path $repositoryRoot 'tests/fixtures/synthetic/rebaseline-record.schema.json'
    foreach ($file in Get-ChildItem -LiteralPath $RecordsDirectory -Filter '*.json' -File | Sort-Object Name) {
        $recordText = Get-Content -LiteralPath $file.FullName -Raw
        if (-not (Test-Json -Json $recordText -SchemaFile $schema -ErrorAction Stop)) {
            throw "Invalid rebaseline review record: '$($file.FullName)'."
        }
        $record = Read-ConformanceJson -Path $file.FullName
        $approvalTime = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse($record.approval.approved_at, [ref] $approvalTime) -or
            [string]::IsNullOrWhiteSpace($record.approval.approver) -or
            [string]::IsNullOrWhiteSpace($record.rationale) -or [string]::IsNullOrWhiteSpace($record.semantic_difference)) {
            throw "Incomplete maintainer approval or rationale: '$($file.FullName)'."
        }
        $records += $record
    }
}

$specificationChanged = -not (Test-ConformanceJsonEqual $baseline.specification_set $candidate.specification_set)
# Renaming a case cannot rescue evidence invalidated by a changed dependency or specification.
$historicalGoldens = @{}
foreach ($oldCase in $oldCases.Values) {
    foreach ($golden in $oldCase.metadata.golden_bindings) {
        if (-not $historicalGoldens.ContainsKey($golden.sha256)) { $historicalGoldens[$golden.sha256] = @() }
        $historicalGoldens[$golden.sha256] += $oldCase
    }
}
foreach ($newCase in $newCases.Values) {
    foreach ($golden in $newCase.metadata.golden_bindings) {
        if (-not $historicalGoldens.ContainsKey($golden.sha256)) { continue }
        $newConfiguration = $candidate.tokens.configuration | Where-Object { $_.token -ceq $newCase.identity.configuration }
        $unchangedDependency = $false
        foreach ($oldCase in $historicalGoldens[$golden.sha256]) {
            $oldConfiguration = $baseline.tokens.configuration | Where-Object { $_.token -ceq $oldCase.identity.configuration }
            if (-not $specificationChanged -and $oldConfiguration.sha256 -ceq $newConfiguration.sha256 -and
                (Test-ConformanceJsonEqual $oldCase.metadata.fixture_binding $newCase.metadata.fixture_binding)) {
                $unchangedDependency = $true
                break
            }
        }
        if (-not $unchangedDependency) {
            throw "Historical golden is stale for the candidate dependency set: '$($newCase.identity.case_id)'."
        }
    }
}
$reviewedReplacements = 0
foreach ($entry in $oldCases.GetEnumerator()) {
    if (-not $newCases.ContainsKey($entry.Key)) { continue }
    $oldCase = $entry.Value
    $newCase = $newCases[$entry.Key]
    if (-not (Test-ConformanceJsonEqual $oldCase.identity $newCase.identity)) {
        throw "Immutable case identity changed: '$($entry.Key)'."
    }
    $oldGoldens = @($oldCase.metadata.golden_bindings)
    $newGoldens = @($newCase.metadata.golden_bindings)
    $dependenciesChanged = $specificationChanged -or
        -not (Test-ConformanceJsonEqual $oldCase.metadata.fixture_binding $newCase.metadata.fixture_binding)
    if ($dependenciesChanged) {
        foreach ($golden in $newGoldens) {
            if ($golden.sha256 -cin @($oldGoldens.sha256)) {
                throw "Golden evidence is stale after source, generator, oracle, or specification change: '$($entry.Key)'."
            }
        }
    }
    if (Test-ConformanceJsonEqual $oldGoldens $newGoldens) { continue }
    if ($oldGoldens.Count -ne $newGoldens.Count) {
        throw "Changing the golden set requires a new case identifier and corpus creation/rebaseline review: '$($entry.Key)'."
    }
    for ($index = 0; $index -lt $newGoldens.Count; $index++) {
        if ($oldGoldens[$index].sha256 -ceq $newGoldens[$index].sha256) { continue }
        $matches = @(Find-ConformanceApprovedGoldenReview -Case $newCase -NewSha256 $newGoldens[$index].sha256 `
            -OldSha256 $oldGoldens[$index].sha256 -Catalog $candidate -Records $records -RepositoryRoot $repositoryRoot)
        if ($matches.Count -ne 1) {
            throw "Golden replacement requires exactly one matching approved review record: '$($entry.Key)'."
        }
        $reviewedReplacements++
    }
}
# New case identifiers may reuse previously admitted goldens. A newly introduced golden still
# needs its own deliberate review; renaming the case is not approval for new expected bytes.
foreach ($newCase in $newCases.Values) {
    if ($oldCases.ContainsKey($newCase.identity.case_id)) { continue }
    foreach ($newGolden in $newCase.metadata.golden_bindings) {
        if ($historicalGoldens.ContainsKey($newGolden.sha256)) { continue }
        $matches = @(Find-ConformanceApprovedGoldenReview -Case $newCase -NewSha256 $newGolden.sha256 `
            -Catalog $candidate -Records $records -RepositoryRoot $repositoryRoot)
        if ($matches.Count -ne 1) {
            throw "A new case introducing new golden bytes requires exactly one approved review record: '$($newCase.identity.case_id)'."
        }
        $reviewedReplacements++
    }
}
Write-Host "PASS: immutable CV1 identities and $reviewedReplacements approved golden replacements."
