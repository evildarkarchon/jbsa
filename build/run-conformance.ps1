<#
.SYNOPSIS
Runs the public-interface CV1 catalog and writes one independently gated result per case.
.PARAMETER RepositoryRoot
Repository containing the immutable catalog and redistributable fixture corpus.
.PARAMETER OutputDirectory
New, empty evidence directory. Ordinary execution never writes fixture or golden bytes.
.PARAMETER Mode
Hosted forbids the local oracle and corpus. Local enables explicitly registered oracle steps.
.PARAMETER RegistrationPath
Optional digest-bound public-interface adapter registrations; absence makes product cases INVALID.
.PARAMETER CodecProfilePath
Exact codec/provider profile used by the candidate; its digest is part of every result.
.NOTES
Exit 0 means every hosted case passed; 1 means at least one case did not pass; 2 means the
catalog or report could not be trusted. Harness self-tests never award Automated Conformance.
#>
[CmdletBinding()]
param(
    [string] $RepositoryRoot = ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))),
    [string] $OutputDirectory = 'target/conformance',
    [ValidateSet('Hosted', 'Local')] [string] $Mode = 'Hosted',
    [string] $RegistrationPath,
    [string] $CodecProfilePath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'conformance-catalog.ps1')
. (Join-Path $PSScriptRoot 'conformance-evidence.ps1')
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')
. (Join-Path $PSScriptRoot 'conformance-execution.ps1')

try {
    if ($env:GITHUB_ACTIONS -ceq 'true' -and $Mode -cne 'Hosted') { throw 'Hosted CI cannot select local oracle or corpus execution.' }
    $RepositoryRoot = [IO.Path]::GetFullPath($RepositoryRoot)
    $OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory, $RepositoryRoot)
    # Evidence must stay in build output: this also excludes the oracle and reference trees.
    $buildOutput = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'target')) + [IO.Path]::DirectorySeparatorChar
    if (-not $OutputDirectory.StartsWith($buildOutput, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Evidence output must be a new directory beneath the repository target directory.'
    }
    if (Test-Path -LiteralPath $OutputDirectory) { throw 'Evidence output already exists; use a fresh directory.' }
    [void][IO.Directory]::CreateDirectory($OutputDirectory)
    $catalogPath = Join-Path $RepositoryRoot 'tests/conformance/catalog.json'
    $catalog = Read-ConformanceCatalog -Path $catalogPath -RepositoryRoot $RepositoryRoot
    $catalogDigest = Get-ConformanceFileDigest -Path $catalogPath
    $specificationDigest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes((ConvertTo-ConformanceCanonicalJson $catalog.specification_set)))).ToLowerInvariant()
    $registrationDigest = $null
    $registrations = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    if ($RegistrationPath) {
        $RegistrationPath = [IO.Path]::GetFullPath($RegistrationPath, $RepositoryRoot)
        $registrationDigest = Get-ConformanceFileDigest -Path $RegistrationPath
        $registrationDocument = Get-Content -Raw -LiteralPath $RegistrationPath | ConvertFrom-Json -AsHashtable -Depth 100
        foreach ($registration in $registrationDocument.cases) {
            if ($registration.case_id -cnotin $catalog.cases.identity.case_id -or -not $registrations.TryAdd($registration.case_id, $registration)) {
                throw 'Unknown or duplicate case in adapter registrations.'
            }
        }
    }
    $contradictions = Get-Content -Raw -LiteralPath (Join-Path $RepositoryRoot 'tests/conformance/contradictions.json') | ConvertFrom-Json -AsHashtable -Depth 100
    foreach ($contradiction in $contradictions.records) {
        if (-not $contradiction.id -or @($contradiction.observations).Count -lt 2 -or -not $contradiction.case_ids) { throw 'Incomplete contradiction record.' }
        foreach ($observation in $contradiction.observations) {
            Assert-ConformanceFields $observation @('authority', 'observation')
            if ([string]::IsNullOrWhiteSpace($observation.authority) -or $null -eq $observation.observation) { throw 'A contradiction must identify its conflicting observations and authorities.' }
        }
        foreach ($id in $contradiction.case_ids) { if ($id -cnotin $catalog.cases.identity.case_id) { throw 'Contradiction names an unknown case.' } }
    }
    $prerequisiteErrors = [Collections.Generic.List[string]]::new()
    if (-not $IsWindows) { $prerequisiteErrors.Add('Automated Conformance requires a hosted Windows environment.') }
    try {
        & (Join-Path $PSScriptRoot 'verify-fixture-corpus.ps1') -CorpusRoot (Join-Path $RepositoryRoot 'tests/fixtures/synthetic') | Out-Null
    }
    catch { $prerequisiteErrors.Add('Fixture provenance audit: ' + $_.Exception.Message) }
    $candidateArtifacts = @()
    foreach ($module in @('jbsa', 'jbsa-cli')) {
        $jars = @(Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot "$module/target") -Filter "$module-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
        if ($jars.Count -eq 1) {
            $candidateArtifacts += @{ path = [IO.Path]::GetRelativePath($RepositoryRoot, $jars[0].FullName).Replace('\', '/'); sha256 = Get-ConformanceFileDigest $jars[0].FullName }
        }
        else { $prerequisiteErrors.Add("Exactly one packaged $module candidate is required.") }
    }
    $codecDigest = $null
    if ($CodecProfilePath) { $codecDigest = Get-ConformanceFileDigest ([IO.Path]::GetFullPath($CodecProfilePath, $RepositoryRoot)) }
    else { $prerequisiteErrors.Add('A pinned codec/provider profile is required.') }
    $results = @()
    foreach ($case in $catalog.cases) {
        $started = [DateTimeOffset]::UtcNow
        $timer = [Diagnostics.Stopwatch]::StartNew()
        $configuration = @($catalog.tokens.configuration | Where-Object { $_.token -ceq $case.identity.configuration })[0]
        $configurationValue = Get-Content -Raw -LiteralPath (Join-Path $RepositoryRoot $configuration.path) | ConvertFrom-Json -AsHashtable -Depth 100
        $reason = @($prerequisiteErrors)
        if (-not $registrations.ContainsKey($case.identity.case_id)) { $reason += 'No public-interface execution adapter is registered for this case.' }
        if ($case.metadata.fixture_binding.state -eq 'missing') { $reason += $case.metadata.fixture_binding.missing_reason }
        $assertions = @($case.metadata.assertions | ForEach-Object {
            [ordered]@{ assertion_id = $_; applicability = 'REQUIRED'; result = 'INVALID'; expected = $null; observed = $null; evidence = @() }
        })
        $record = [ordered]@{
            case_id = $case.identity.case_id; contract = 'conformance-v1'; case_manifest_sha256 = $catalogDigest
            specification_sha256 = $specificationDigest
            adapter_registration_sha256 = $registrationDigest; configuration_sha256 = $configuration.sha256
            candidate_artifacts = $candidateArtifacts; codec_profile_sha256 = $codecDigest; compatibility_profile = $configurationValue.compatibility_profile
            fixture_digests = @($case.metadata.fixture_binding.files | ForEach-Object { $_.sha256 })
            golden_digests = @($case.metadata.golden_bindings | ForEach-Object { $_.sha256 })
            oracle = $null; validators = @(); environment = [ordered]@{ os = [Environment]::OSVersion.VersionString; architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString(); powershell = $PSVersionTable.PSVersion.ToString(); mode = $Mode }
            start_time = $started.ToString('O'); duration_ms = $timer.ElapsedMilliseconds
            result = 'INVALID'; assertions = $assertions; reason = ($reason -join ' ')
        }
        $conflicts = @($contradictions.records | Where-Object { $case.identity.case_id -cin $_.case_ids })
        if ($conflicts.Count) {
            $record.reason = 'Registered authority contradiction blocks this case.'
            $record.contradictions = $conflicts
        }
        elseif ($reason.Count -eq 0) {
            $execution = Invoke-ConformanceRegisteredCase -Case $case -Registration $registrations[$case.identity.case_id] -RepositoryRoot $RepositoryRoot -EvidenceDirectory (Join-Path $OutputDirectory $case.identity.case_id) -Mode $Mode -ConfigurationSha256 $configuration.sha256 -SpecificationSha256 $specificationDigest -CandidateArtifacts $candidateArtifacts -CodecProfileSha256 $codecDigest
            $record.result = $execution.result; $record.reason = $execution.reason
            $record.oracle = $execution.oracle; $record.validators = $execution.validators
            if ($execution.assertions.Count -eq $assertions.Count) { $record.assertions = $execution.assertions }
            $record.execution_evidence = $execution.evidence
        }
        $record.duration_ms = $timer.ElapsedMilliseconds
        $results += $record
    }
    if ($RegistrationPath -and (Get-ConformanceFileDigest $RegistrationPath) -cne $registrationDigest) { throw 'Adapter registrations changed during execution.' }
    $report = Write-ConformanceReport -Catalog $catalog -Results $results -Directory $OutputDirectory
    Write-Output "Conformance evidence: $OutputDirectory"
    if (@($results | Where-Object { $_.result -cne 'PASS' }).Count -gt 0) { exit 1 }
    exit 0
}
catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
    exit 2
}
