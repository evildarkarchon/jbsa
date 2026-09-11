<# .SYNOPSIS Runs all proposed TES4 successor cases as untrusted evidence pending maintainer review. #>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDirectory,
    [ValidateSet('bsa-067', 'bsa-068')][string]$Family = 'bsa-067')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'conformance-catalog.ps1')
. (Join-Path $PSScriptRoot 'conformance-execution.ps1')
. (Join-Path $PSScriptRoot 'bsa-cv1-review-evidence.ps1')
$output = [IO.Path]::GetFullPath($OutputDirectory, $root)
if (-not $output.StartsWith((Join-Path $root 'target') + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $output)) {
    throw 'Review evidence requires a new directory beneath repository target.'
}
[IO.Directory]::CreateDirectory($output) | Out-Null
$proposalDirectory = if ($Family -eq 'bsa-068') { 'docs/reviews/issue42-cv1' } else { 'docs/reviews/issue38-cv1' }
$proposal = Join-Path $root "$proposalDirectory/catalog.json"
$catalog = Read-ConformanceCatalog -Path $proposal -RepositoryRoot $root
$successors = (Get-Content -Raw -LiteralPath (Join-Path $root "$proposalDirectory/review.json") | ConvertFrom-Json).supersessions.proposed_case_id
$cases = @($catalog.cases | Where-Object { $_.identity.archive_family -eq $Family -and $_.identity.case_id -in $successors })
if ($Family -eq 'bsa-067' -and $cases.Count -ne 32) { throw 'The proposal must retain every one of the 32 TES4 cells.' }
if ($cases.Count -eq 0) { throw 'The proposal has no successor cases to execute.' }
$oracleInputs = Join-Path $root $(if ($Family -eq 'bsa-068') { 'target/bsa068-cv1-review-inputs' } else { 'target/bsa-cv1-review-inputs' })
[IO.Directory]::CreateDirectory($oracleInputs) | Out-Null
foreach ($mode in @('stored', 'zlib')) {
    [IO.File]::WriteAllBytes((Join-Path $oracleInputs "$mode.bsa"),
        [Convert]::FromHexString([IO.File]::ReadAllText((Join-Path $root "$proposalDirectory/oracle/$mode.hex")).Trim()))
}
$artifacts = @(foreach ($module in @('jbsa', 'jbsa-cli')) {
    $jar = @(Get-ChildItem -LiteralPath (Join-Path $root "$module/target") -Filter "$module-*.jar" |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
    if ($jar.Count -ne 1) { throw 'Exactly one packaged artifact per product module is required.' }
    @{ path = [IO.Path]::GetRelativePath($root, $jar[0].FullName).Replace('\', '/'); sha256 = Get-ConformanceFileDigest $jar[0].FullName }
})
$profile = Join-Path $root 'jbsa/src/main/resources/META-INF/jbsa-codec-profile.json'
$selectedJar = Join-Path $root (@($artifacts | Where-Object { $_.path -like 'jbsa/target/*' })[0].path)
$packagedProfile = Get-BsaPackagedProfile -Jar $selectedJar -SourceProfile $profile
$profileDigest = $packagedProfile.sha256
$specDigest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
    [Text.Encoding]::UTF8.GetBytes((ConvertTo-ConformanceCanonicalJson $catalog.specification_set)))).ToLowerInvariant()
$pwsh = (Get-Command pwsh).Source
$python = (Get-Command python).Source
$adapter = Join-Path $PSScriptRoot 'observe-bsa-cv1.ps1'
$scanner = Join-Path $PSScriptRoot 'bsa-cv1-expectations.py'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$runtimeVersion = (& $java --version) -join "`n"
$inputs = @($adapter, $scanner, (Join-Path $PSScriptRoot 'validate-bsa-wire.py'),
    (Join-Path $PSScriptRoot 'bsa-cv1-review-evidence.ps1'), (Join-Path $PSScriptRoot 'conformance-evidence.ps1'),
    (Join-Path $PSScriptRoot 'conformance-adapters.ps1'), $java) +
    @(Get-ChildItem -LiteralPath (Join-Path $root 'jbsa-conformance-tests/target/test-classes/io/github/evildarkarchon/jbsa/verification') -Filter 'Bsa*.class' | ForEach-Object FullName) +
    @(Get-ChildItem -LiteralPath (Join-Path $root 'jbsa-conformance-tests/src/test/java/io/github/evildarkarchon/jbsa/verification') -Filter 'Bsa68*.java' | ForEach-Object FullName)
if ($Family -eq 'bsa-068') { $inputs += Join-Path $PSScriptRoot 'bsa68-valid-split-recipe.json' }
$boundInputs = @($inputs | ForEach-Object { @{ path = $_; sha256 = Get-ConformanceFileDigest $_ } })
$results = @()
$registrations = @()
foreach ($case in $cases) {
    $config = @($catalog.tokens.configuration | Where-Object { $_.token -eq $case.identity.configuration })[0]
    $registration = @{ case_id = $case.identity.case_id;
        command = @{ executable = $pwsh; sha256 = Get-ConformanceFileDigest $pwsh;
            arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', $adapter); inputs = $boundInputs };
        validators = @(); oracle_input_role = 'archive'; oracle_arguments = @('unpack', '{input}', '{output}', '-mt:no') }
    if ($case.identity.fixture -eq 'bsa-068-scenario-source-splitting-v1') {
        # The real family split limit requires streaming several GiB; retain a bounded recorded deadline.
        $registration.command.timeout_seconds = 1200
    }
    if ($case.metadata.expected_behavior -eq 'accept' -and $case.identity.fixture -notlike 'bsa-068-scenario-*') {
        $assertion = if ($case.identity.operation -eq 'encode') { 'jbsa-to-oracle' } else { 'decode-semantic-projection' }
        $registration.validators = @(@{ input_role = 'archive'; assertion_id = $assertion;
            tool = @{ identity = 'jbsa-independent-specification-bsa-scanner'; path = $python;
                sha256 = Get-ConformanceFileDigest $python; adapter_version = '1'; kind = 'archive';
                independent = $true; derived_from_reference = $false };
            arguments = @($scanner, '{input}') })
    }
    $registrations += $registration
    $key = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::UTF8.GetBytes($case.identity.case_id))).ToLowerInvariant().Substring(0, 16)
    $execution = Invoke-ConformanceRegisteredCase -Case $case -Registration $registration `
        -RepositoryRoot $root -EvidenceDirectory (Join-Path $output $key) -Mode Local `
        -ConfigurationSha256 $config.sha256 -SpecificationSha256 $specDigest `
        -CandidateArtifacts $artifacts -CodecProfileSha256 $profileDigest
    $results += @{ case_id = $case.identity.case_id; status = 'UNTRUSTED_PENDING_MAINTAINER_APPROVAL';
        comparison_result = $execution.result; execution = $execution }
    Write-Host "$($execution.result): $($case.identity.case_id) $($execution.reason)"
}
$registrationPath = Join-Path $output 'registrations.json'
[IO.File]::WriteAllText($registrationPath, (@{ cases = $registrations } | ConvertTo-Json -Depth 100))
$report = @{ status = 'UNTRUSTED_PENDING_MAINTAINER_APPROVAL'; automated_conformance = $false;
    proposed_catalog_sha256 = Get-ConformanceFileDigest $proposal; codec_profile_sha256 = $profileDigest;
    java_runtime = @{ executable = $java; sha256 = Get-ConformanceFileDigest $java; version = $runtimeVersion };
    adapter_registration_sha256 = Get-ConformanceFileDigest $registrationPath;
    candidate_artifacts = $artifacts; results = $results }
[IO.File]::WriteAllText((Join-Path $output 'review-report.json'), ($report | ConvertTo-Json -Depth 100))
if (@($results | Where-Object { $_.comparison_result -ne 'PASS' }).Count) { exit 1 }
exit 0
