<#
.SYNOPSIS
Exercises the public catalog loader, including fail-closed manifest mutations.
.NOTES
Writes only disposable copies; committed fixture, configuration, and catalog bytes stay immutable.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repositoryRoot 'build/conformance-catalog.ps1')
$catalogPath = Join-Path $PSScriptRoot 'catalog.json'
$catalog = Read-ConformanceCatalog -Path $catalogPath -RepositoryRoot $repositoryRoot
if ($catalog.cases.Count -lt 150) { throw 'Expected the full required catalog.' }
$originalDigest = Get-ConformanceFileDigest -Path $catalogPath
$temporaryPath = Join-Path ([IO.Path]::GetTempPath()) ('jbsa-catalog-test-' + [Guid]::NewGuid().ToString('N') + '.json')

<#
.SYNOPSIS
Requires the loader to reject a modified copy without touching committed evidence.
.PARAMETER Mutation
An in-memory mutation applied to a fresh catalog copy.
#>
function Assert-CatalogRejected {
    param([string] $Name, [scriptblock] $Mutation)
    $copy = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json -Depth 100
    & $Mutation $copy
    $copy | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $temporaryPath -Encoding utf8
    $rejected = $false
    try { $null = Read-ConformanceCatalog -Path $temporaryPath -RepositoryRoot $repositoryRoot }
    catch { $rejected = $true }
    if (-not $rejected) { throw "Catalog mutation was accepted: $Name" }
    Write-Host "PASS: $Name"
}

try {
    Assert-CatalogRejected 'duplicate case' { param($c) $c.cases += $c.cases[0] }
    Assert-CatalogRejected 'identity serialization' { param($c) $c.cases[0].identity.case_id += '-changed' }
    Assert-CatalogRejected 'extra identity field' { param($c) $c.cases[0].identity | Add-Member unexpected 'value' }
    Assert-CatalogRejected 'missing required base case' { param($c) $c.cases = @($c.cases | Select-Object -Skip 1) }
    Assert-CatalogRejected 'missing fixture digest' { param($c) $c.tokens.fixture[0].sha256 = '' }
    Assert-CatalogRejected 'missing governing specification' { param($c) $c.specification_set = @($c.specification_set | Select-Object -Skip 1) }
    Assert-CatalogRejected 'incorrect configuration digest' { param($c) $c.tokens.configuration[0].sha256 = '0' * 64 }
    Assert-CatalogRejected 'missing targeted coverage' { param($c) foreach ($case in $c.cases) { $case.metadata.coverage_items = @($case.metadata.coverage_items | Where-Object { $_ -ne 'input-empty' }) } }
    Assert-CatalogRejected 'unknown assertion' { param($c) $c.cases[0].metadata.assertions += 'unknown-assertion' }
    Assert-CatalogRejected 'semantic decode cannot use exact comparator' {
        param($c) ($c.assertions | Where-Object { $_.id -ceq 'decode-semantic-projection' }).kind = 'exact'
    }
    Assert-CatalogRejected 'semantic encode cannot use exact comparator' {
        param($c) ($c.assertions | Where-Object { $_.id -ceq 'oracle-to-jbsa' }).kind = 'exact'
    }
    foreach ($direction in @('oracle-to-jbsa', 'jbsa-to-oracle')) {
        Assert-CatalogRejected "positive encode requires $direction" {
            param($c)
            $case = $c.cases | Where-Object { $_.identity.operation -ceq 'encode' -and $_.metadata.expected_behavior -ceq 'accept' } | Select-Object -First 1
            $case.metadata.assertions = @($case.metadata.assertions | Where-Object { $_ -cne $direction })
        }
    }
    Assert-CatalogRejected 'unknown governing requirement' { param($c) $c.assertions[0].requirements = @('JBSA-CONF-999') }
    Assert-CatalogRejected 'missing evidence declaration' { param($c) $c.cases[0].metadata.PSObject.Properties.Remove('evidence_required') }
    Assert-CatalogRejected 'DDS encode target missing' {
        param($c)
        $case = $c.cases | Where-Object { $_.identity.archive_family -like '*dx10*' -and $_.identity.operation -eq 'encode' } | Select-Object -First 1
        $case.identity.configuration = 'standard-v1'
        $case.identity.case_id = "CV1-$($case.identity.archive_family).encode.$($case.identity.fixture).$($case.identity.codec).standard-v1"
    }
    Assert-CatalogRejected 'unsupported codec accepted' { param($c) ($c.cases | Where-Object { $_.metadata.expected_behavior -eq 'reject' } | Select-Object -First 1).metadata.expected_behavior = 'accept' }
    foreach ($excludedPath in @('tests/fixtures/local/oracle/BSArch.exe', 'TES5Edit/README.md', 'tests/conformance/../fixtures/local/missing.bin')) {
        $rejectedBeforeRead = $false
        try { $null = Resolve-ConformanceBinding ([pscustomobject]@{ path = $excludedPath; sha256 = ('0' * 64) }) $repositoryRoot }
        catch { $rejectedBeforeRead = $_.Exception.Message -like '*excluded from redistributable evidence*' }
        if (-not $rejectedBeforeRead) { throw "Excluded path was not rejected before content access: '$excludedPath'." }
    }
    Write-Host 'PASS: excluded local and reference paths rejected before reading bytes'
    if ((Get-ConformanceFileDigest -Path $catalogPath) -cne $originalDigest) { throw 'Loader modified catalog bytes.' }
    Write-Host "PASS: catalog integrity ($($catalog.cases.Count) required cases)"
}
finally {
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
}
