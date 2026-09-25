<# .SYNOPSIS Runs all proposed General BA2 successor cases as untrusted evidence pending maintainer review. #>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$OutputDirectory)
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
$proposal = Join-Path $root 'docs/reviews/issue39-cv1/catalog.json'
$catalog = Read-ConformanceCatalog -Path $proposal -RepositoryRoot $root
$cases = @($catalog.cases | Where-Object { $_.identity.archive_family -eq 'fo4-gnrl-v1' })
if ($cases.Count -ne 33) { throw 'The proposal must retain every one of the 33 General BA2 cells.' }
$oracleInputs = Join-Path $root 'target/ba2-cv1-review-inputs'
[IO.Directory]::CreateDirectory($oracleInputs) | Out-Null
foreach ($mode in @('stored', 'zlib')) {
    [IO.File]::WriteAllBytes((Join-Path $oracleInputs "$mode.ba2"),
        [Convert]::FromHexString([IO.File]::ReadAllText((Join-Path $root "docs/reviews/issue39-cv1/oracle/$mode.hex")).Trim()))
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
$adapter = Join-Path $PSScriptRoot 'observe-ba2-cv1.ps1'
$scanner = Join-Path $PSScriptRoot 'ba2-cv1-expectations.py'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$runtimeVersion = (& $java --version) -join "`n"
$inputs = @($adapter, $scanner, (Join-Path $PSScriptRoot 'validate-ba2-wire.py'),
    (Join-Path $PSScriptRoot 'bsa-cv1-review-evidence.ps1'), (Join-Path $PSScriptRoot 'conformance-evidence.ps1'),
    (Join-Path $PSScriptRoot 'conformance-adapters.ps1'), $java) +
    @(Get-ChildItem -LiteralPath (Join-Path $root 'jbsa-conformance-tests/target/test-classes/io/github/evildarkarchon/jbsa/verification') -Filter 'Ba2PublicObservation*.class' | ForEach-Object FullName)
$boundInputs = @($inputs | ForEach-Object { @{ path = $_; sha256 = Get-ConformanceFileDigest $_ } })
$results = @()
$registrations = @()
foreach ($case in $cases) {
    $config = @($catalog.tokens.configuration | Where-Object { $_.token -eq $case.identity.configuration })[0]
    $registration = @{ case_id = $case.identity.case_id;
        command = @{ executable = $pwsh; sha256 = Get-ConformanceFileDigest $pwsh;
            arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', $adapter); inputs = $boundInputs };
        validators = @(); oracle_input_role = 'archive'; oracle_arguments = @('unpack', '{input}', '{output}', '-mt:no') }
    if ($case.metadata.expected_behavior -eq 'accept') {
        $assertion = if ($case.identity.operation -eq 'encode') { 'jbsa-to-oracle' } else { 'decode-semantic-projection' }
        $registration.validators = @(@{ input_role = 'archive'; assertion_id = $assertion;
            tool = @{ identity = 'jbsa-independent-specification-ba2-scanner'; path = $python;
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
