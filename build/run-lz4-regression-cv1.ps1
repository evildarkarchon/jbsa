<#
.SYNOPSIS
Reexecutes the 109 admitted BSA 067, General BA2 and PC DDS CV1 cases after specification rebinding.
.DESCRIPTION
Uses the active catalog and packaged candidate artifacts with fresh independent scanner,
DirectXTex and local pinned oracle observations. This scoped regression does not award
all-family Automated Conformance, Binary Conformance or performance qualification.
.PARAMETER OutputDirectory
A new directory beneath target containing complete subprocess and comparison evidence.
#>
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
$proposal = Join-Path $root 'tests/conformance/catalog.json'
$catalog = Read-ConformanceCatalog -Path $proposal -RepositoryRoot $root
$cases = @($catalog.cases | Where-Object { @($_.metadata.golden_bindings).Count -gt 0 })
$counts = @{ 'bsa-067' = 32; 'fo4-gnrl-v1' = 33; 'fo4-dx10-v1' = 44 }
if ($cases.Count -ne 109) { throw 'The admitted regression scope must contain exactly 109 cases.' }
foreach ($family in $counts.Keys) {
    if (@($cases | Where-Object { $_.identity.archive_family -eq $family }).Count -ne $counts[$family]) {
        throw "Incomplete admitted family scope: $family"
    }
}
# Historical oracle-produced input bytes remain exact read-only fixture inputs;
# fresh current-code decode and oracle encode cross-checks are executed below.
foreach ($fixtureGroup in @(
    @{ kind='bsa'; issue=38; extension='bsa'; modes=@('stored','zlib') },
    @{ kind='ba2'; issue=39; extension='ba2'; modes=@('stored','zlib') },
    @{ kind='dds'; issue=40; extension='ba2'; modes=@('base','single') })) {
    $oracleInputs = Join-Path $root "target/$($fixtureGroup.kind)-cv1-review-inputs"
    [IO.Directory]::CreateDirectory($oracleInputs) | Out-Null
    foreach ($mode in $fixtureGroup.modes) {
        $target = Join-Path $oracleInputs "$mode.$($fixtureGroup.extension)"
        [IO.File]::WriteAllBytes($target, [Convert]::FromHexString([IO.File]::ReadAllText(
            (Join-Path $root "docs/reviews/issue$($fixtureGroup.issue)-cv1/oracle/$mode.hex")).Trim()))
        if ($fixtureGroup.kind -eq 'dds') {
            $boundWork = Join-Path $root "target/dds-cv1-oracle-$mode"
            [IO.Directory]::CreateDirectory($boundWork) | Out-Null
            Copy-Item -LiteralPath $target -Destination (Join-Path $boundWork 'oracle.ba2') -Force
        }
    }
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
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$runtimeVersion = (& $java --version) -join "`n"
$ddsValidator = Join-Path $PSScriptRoot 'validate-dds-cv1-directx.py'
$texdiag = Join-Path $root 'build/target/directxtex-may2026/texdiag.exe'
$inputs = @($PSCommandPath, $ddsValidator, $texdiag, $java,
    (Join-Path $PSScriptRoot 'bsa-cv1-review-evidence.ps1'),
    (Join-Path $PSScriptRoot 'conformance-execution.ps1'),
    (Join-Path $PSScriptRoot 'conformance-catalog.ps1'),
    (Join-Path $PSScriptRoot 'conformance-evidence.ps1'),
    (Join-Path $PSScriptRoot 'conformance-adapters.ps1'))
foreach ($kind in @('bsa','ba2','dds')) {
    $inputs += @((Join-Path $PSScriptRoot "observe-$kind-cv1.ps1"),
        (Join-Path $PSScriptRoot "$kind-cv1-expectations.py"),
        (Join-Path $PSScriptRoot "validate-$kind-wire.py"))
}
$inputs += @(Get-ChildItem -LiteralPath (Join-Path $root 'jbsa-conformance-tests/target/test-classes/io/github/evildarkarchon/jbsa/verification') -Filter '*PublicObservation*.class' | ForEach-Object FullName)
$boundInputs = @($inputs | ForEach-Object { @{ path = $_; sha256 = Get-ConformanceFileDigest $_ } })
$results = @()
$registrations = @()
foreach ($case in $cases) {
    $kind = switch ($case.identity.archive_family) { 'bsa-067' { 'bsa' } 'fo4-gnrl-v1' { 'ba2' } 'fo4-dx10-v1' { 'dds' } }
    $adapter = Join-Path $PSScriptRoot "observe-$kind-cv1.ps1"
    $scanner = Join-Path $PSScriptRoot "$kind-cv1-expectations.py"
    $config = @($catalog.tokens.configuration | Where-Object { $_.token -eq $case.identity.configuration })[0]
    $registration = @{ case_id = $case.identity.case_id;
        command = @{ executable = $pwsh; sha256 = Get-ConformanceFileDigest $pwsh;
            arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', $adapter); inputs = $boundInputs };
        validators = @(); oracle_input_role = 'archive'; oracle_arguments = @('unpack', '{input}', '{output}', '-mt:no') }
    if ($case.metadata.expected_behavior -eq 'accept') {
        $assertion = if ($case.identity.operation -eq 'encode') { 'jbsa-to-oracle' } else { 'decode-semantic-projection' }
        $selection = $kind -eq 'dds' -and $case.metadata.fixture_binding.generator.configuration.recipe -eq 'dds-reconstruction-selection'
        if ($selection) { $assertion = 'coverage-dds-reconstruction-selection' }
        $scannerArguments = @($scanner, '{input}')
        $directxArguments = @($ddsValidator, '{input}', $texdiag)
        if ($selection) { $scannerArguments += 'SELECTION'; $directxArguments += 'SELECTION' }
        $registration.validators = @(@{ input_role = 'archive'; assertion_id = $assertion;
            tool = @{ identity = "jbsa-independent-specification-$kind-scanner"; path = $python;
                sha256 = Get-ConformanceFileDigest $python; adapter_version = '1'; kind = 'archive';
                independent = $true; derived_from_reference = $false };
            arguments = $scannerArguments })
        if ($kind -eq 'dds') { $registration.validators += @(@{ input_role = 'archive'; assertion_id = $assertion;
            tool = @{ identity = 'Microsoft-DirectXTex-may2026-via-independent-DDS-reconstruction'; path = $python;
                sha256 = Get-ConformanceFileDigest $python; adapter_version = '1'; kind = 'dds';
                independent = $true; derived_from_reference = $false };
            arguments = $directxArguments }) }
    }
    $registrations += $registration
    $key = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData(
        [Text.Encoding]::UTF8.GetBytes($case.identity.case_id))).ToLowerInvariant().Substring(0, 16)
    $execution = Invoke-ConformanceRegisteredCase -Case $case -Registration $registration `
        -RepositoryRoot $root -EvidenceDirectory (Join-Path $output $key) -Mode Local `
        -ConfigurationSha256 $config.sha256 -SpecificationSha256 $specDigest `
        -CandidateArtifacts $artifacts -CodecProfileSha256 $profileDigest
    $results += @{ case_id = $case.identity.case_id; status = $execution.result;
        comparison_result = $execution.result; execution = $execution }
    Write-Host "$($execution.result): $($case.identity.case_id) $($execution.reason)"
}
$registrationPath = Join-Path $output 'registrations.json'
[IO.File]::WriteAllText($registrationPath, (@{ cases = $registrations } | ConvertTo-Json -Depth 100))
$passed = @($results | Where-Object { $_.comparison_result -eq 'PASS' }).Count
$report = @{ status = $(if ($passed -eq 109) { 'SCOPED_PASS' } else { 'SCOPED_FAILED' }); automated_conformance = $false;
    scope = '109 admitted BSA 067, General BA2 and PC DDS archive cases; not all-family qualification';
    recorded_at = [DateTimeOffset]::UtcNow.ToString('o'); passed = $passed; expected = 109;
    active_catalog_sha256 = Get-ConformanceFileDigest $proposal; codec_profile_sha256 = $profileDigest;
    java_runtime = @{ executable = $java; sha256 = Get-ConformanceFileDigest $java; version = $runtimeVersion };
    adapter_registration_sha256 = Get-ConformanceFileDigest $registrationPath;
    candidate_artifacts = $artifacts; results = $results }
[IO.File]::WriteAllText((Join-Path $output 'current-cv1.json'), ($report | ConvertTo-Json -Depth 100))
if (@($results | Where-Object { $_.comparison_result -ne 'PASS' }).Count) { exit 1 }
exit 0
