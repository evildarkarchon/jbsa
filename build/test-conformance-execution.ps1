<#
.SYNOPSIS
Tests golden comparison through a real public adapter process, including tampering and contradictions.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'conformance-execution.ps1')
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$temporary = Join-Path $root ('target/conformance-execution-test-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($temporary)
$id = 'CV1-global.command.test.none.default'
$adapter = Join-Path $temporary 'adapter.ps1'
[IO.File]::WriteAllText($adapter, 'param([string] $Request) ''{"case_id":"CV1-global.command.test.none.default","assertions":[{"assertion_id":"exit","observed":0}]}''')
$golden = Join-Path $temporary 'golden.json'
$configDigest = 'a' * 64
$specDigest = 'b' * 64
[IO.File]::WriteAllText($golden, (@{ contract = 'conformance-v1'; case_id = $id; configuration_sha256 = $configDigest; specification_sha256 = $specDigest; assertions = @(@{ assertion_id = 'exit'; kind = 'exact'; expected = 0 }) } | ConvertTo-Json -Depth 30))
$case = @{ identity = @{ case_id = $id; archive_family = 'global'; operation = 'command'; configuration = 'default' }; metadata = @{ assertions = @('exit'); expected_behavior = 'assert-specified-outcome'; fixture_binding = @{ state = 'available'; files = @() }; golden_bindings = @(@{ path = [IO.Path]::GetRelativePath($root, $golden); sha256 = (Get-FileHash $golden -Algorithm SHA256).Hash.ToLowerInvariant() }) } }
$registration = @{ case_id = $id; command = @{ executable = (Get-Command pwsh).Source; sha256 = (Get-FileHash (Get-Command pwsh).Source -Algorithm SHA256).Hash.ToLowerInvariant(); arguments = @('-NoLogo', '-NoProfile', '-File', $adapter); inputs = @(@{ path = $adapter; sha256 = (Get-FileHash $adapter -Algorithm SHA256).Hash.ToLowerInvariant() }) }; validators = @() }
$parameters = @{ Case = $case; Registration = $registration; RepositoryRoot = $root; Mode = 'Hosted'; ConfigurationSha256 = $configDigest; SpecificationSha256 = $specDigest; CandidateArtifacts = @(); CodecProfileSha256 = ('c' * 64) }
$result = Invoke-ConformanceRegisteredCase @parameters -EvidenceDirectory (Join-Path $temporary 'pass')
if ($result.result -cne 'PASS') { throw "Expected pass from independently specified exit observation: $($result.reason)" }
$originalAdapter = [IO.File]::ReadAllText($adapter)
$originalGolden = [IO.File]::ReadAllText($golden)
[IO.File]::WriteAllText($adapter, $originalAdapter.Replace('"assertion_id":"exit"', '"assertion_id":"decode-semantic-projection"'))
$semanticGolden = $originalGolden | ConvertFrom-Json -AsHashtable -Depth 30
$semanticGolden.assertions[0].assertion_id = 'decode-semantic-projection'
[IO.File]::WriteAllText($golden, ($semanticGolden | ConvertTo-Json -Depth 30))
$case.metadata.assertions = @('decode-semantic-projection')
$case.metadata.golden_bindings[0].sha256 = (Get-FileHash $golden).Hash.ToLowerInvariant()
$registration.command.inputs[0].sha256 = (Get-FileHash $adapter).Hash.ToLowerInvariant()
$result = Invoke-ConformanceRegisteredCase @parameters -EvidenceDirectory (Join-Path $temporary 'semantic-kind')
if ($result.result -cne 'INVALID') { throw 'An exact scalar comparison cannot replace the complete semantic projection.' }
[IO.File]::WriteAllText($adapter, $originalAdapter)
[IO.File]::WriteAllText($golden, $originalGolden)
$case.metadata.assertions = @('exit')
$case.metadata.golden_bindings[0].sha256 = (Get-FileHash $golden).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($adapter, 'param([string] $Request) ''{"case_id":"CV1-global.command.test.none.default","assertions":[{"assertion_id":"exit","observed":7}]}''')
$registration.command.inputs[0].sha256 = (Get-FileHash $adapter -Algorithm SHA256).Hash.ToLowerInvariant()
$result = Invoke-ConformanceRegisteredCase @parameters -EvidenceDirectory (Join-Path $temporary 'fail')
if ($result.result -cne 'FAIL') { throw 'Candidate disagreement with an immutable golden must fail.' }
[IO.File]::AppendAllText($golden, ' ')
$result = Invoke-ConformanceRegisteredCase @parameters -EvidenceDirectory (Join-Path $temporary 'tampered')
if ($result.result -cne 'INVALID') { throw 'Changed golden bytes must invalidate execution.' }
$case.metadata.fixture_binding.files = @(@{ path = 'tests/fixtures/local/must-not-read.bin'; sha256 = '0' * 64 })
$result = Invoke-ConformanceRegisteredCase @parameters -EvidenceDirectory (Join-Path $temporary 'hosted-local')
if ($result.result -cne 'INVALID' -or $result.reason -notmatch 'Hosted') { throw 'Hosted local-fixture denial must occur before touching the file.' }

$encodeId = 'CV1-tes3.encode.probe.stored.default'
$projection = @{ archive_family = 'tes3'; wire_version = 0; entry_count = 0; entries = @(); diagnostics = @() }
$oracleArchivePath = Join-Path $temporary 'oracle-archive.bin'
[IO.File]::WriteAllText($oracleArchivePath, 'abc')
$archiveDigest = (Get-FileHash $oracleArchivePath).Hash.ToLowerInvariant()
$encodeGolden = @{ contract = 'conformance-v1'; case_id = $encodeId; configuration_sha256 = $configDigest; specification_sha256 = $specDigest; oracle_sha256 = '4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2'; oracle_archive = @{ path = $oracleArchivePath; sha256 = $archiveDigest }; source_payloads = @(); oracle_candidate_sha256 = $archiveDigest; oracle_candidate_result = 'PASS'; assertions = @(@{ assertion_id = 'oracle-to-jbsa'; kind = 'semantic'; expected = $projection }, @{ assertion_id = 'jbsa-to-oracle'; kind = 'semantic'; expected = $projection }) }
[IO.File]::WriteAllText($golden, ($encodeGolden | ConvertTo-Json -Depth 30))
$adapterSource = @'
param([string] $Request)
$requestData = Get-Content -Raw -LiteralPath $Request | ConvertFrom-Json
$projection = @{ archive_family = 'tes3'; wire_version = 0; entry_count = 0; entries = @(); diagnostics = @() }
if ($requestData.phase -eq 'oracle-to-jbsa') {
    if ([IO.File]::ReadAllText($requestData.input_archive.path) -cne 'abc') { exit 9 }
    @{ case_id = $requestData.case.identity.case_id; assertions = @(@{ assertion_id = 'oracle-to-jbsa'; observed = $projection }) } | ConvertTo-Json -Depth 30 -Compress
} else {
    [IO.File]::WriteAllText('candidate.bin', 'abc')
    @{ case_id = $requestData.case.identity.case_id; assertions = @(@{ assertion_id = 'jbsa-to-oracle'; observed = $projection }); artifacts = @(@{ role = 'archive'; path = 'candidate.bin'; sha256 = (Get-FileHash 'candidate.bin').Hash.ToLowerInvariant() }) } | ConvertTo-Json -Depth 30 -Compress
}
'@
[IO.File]::WriteAllText($adapter, $adapterSource)
$case.identity.case_id = $encodeId
$case.identity.archive_family = 'tes3'
$case.identity.operation = 'encode'
$case.metadata.expected_behavior = 'accept'
$case.metadata.assertions = @('oracle-to-jbsa', 'jbsa-to-oracle')
$case.metadata.fixture_binding.files = @()
$case.metadata.golden_bindings[0].sha256 = (Get-FileHash $golden).Hash.ToLowerInvariant()
$registration.case_id = $encodeId
$registration.command.inputs[0].sha256 = (Get-FileHash $adapter).Hash.ToLowerInvariant()
$registration.oracle_input_role = 'archive'
$validatorOutput = @{ projection = $projection } | ConvertTo-Json -Depth 30 -Compress
$registration.validators = @(@{ tool = @{ path = (Get-Command pwsh).Source; sha256 = $registration.command.sha256; identity = 'project-authored independent probe'; adapter_version = '1'; kind = 'archive'; independent = $true; derived_from_reference = $false }; input_role = 'archive'; assertion_id = 'jbsa-to-oracle'; arguments = @('-NoProfile', '-Command', ('[Console]::Write(''' + $validatorOutput + ''')')) })
$result = Invoke-ConformanceRegisteredCase @parameters -EvidenceDirectory (Join-Path $temporary 'separate-decode')
if ($result.result -cne 'PASS') { throw "Separate real adapter phases failed: $($result.reason)" }
$decodeAssertion = @($result.assertions | Where-Object assertion_id -eq 'oracle-to-jbsa')[0]
$decodeEvidence = Get-Content -Raw -LiteralPath $decodeAssertion.evidence[0].path | ConvertFrom-Json
if ($decodeEvidence.assertions[0].assertion_id -cne 'oracle-to-jbsa') { throw 'Cross-decode assertion must cite its own process observation.' }
if ($env:GITHUB_ACTIONS -ne 'true') {
    $localParameters = @{} + $parameters
    $localParameters.Mode = 'Local'
    $localParameters.RepositoryRoot = $temporary
    $case.metadata.golden_bindings[0].path = $golden
    $localEvidence = Join-Path $temporary 'absent-local-oracle'
    $result = Invoke-ConformanceRegisteredCase @localParameters -EvidenceDirectory $localEvidence
    if ($result.result -cne 'UNAVAILABLE' -or (Test-Path -LiteralPath $localEvidence)) { throw 'An absent local oracle must prevent all candidate execution.' }
}
[IO.File]::WriteAllText($adapter, $adapterSource.Replace('if ($requestData.phase -eq ''oracle-to-jbsa'') {', 'if ($requestData.phase -eq ''oracle-to-jbsa'') { exit 8'))
$registration.command.inputs[0].sha256 = (Get-FileHash $adapter).Hash.ToLowerInvariant()
$result = Invoke-ConformanceRegisteredCase @parameters -EvidenceDirectory (Join-Path $temporary 'failed-decode')
if ($result.result -cne 'INVALID' -or $result.reason -notmatch 'Oracle-to-JBSA decode process') { throw 'A failed separate cross-decode must block the case.' }
Write-Output 'Conformance execution checks passed.'
