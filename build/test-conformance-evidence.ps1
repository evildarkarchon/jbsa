<# .SYNOPSIS
Exercises canonical observations, semantic comparisons, and immutable evidence through the harness helper boundary.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'conformance-evidence.ps1')
if ((ConvertTo-ConformanceCanonicalJson @{ z = @($null, 1); A = @{ b = 2; a = 1 } }) -cne '{"A":{"a":1,"b":2},"z":[null,1]}') {
    throw 'Canonical JSON must sort keys ordinally, preserving arrays and null.'
}
if ((Compare-ConformanceValue -Expected @{ a = 1 } -Observed @{ a = 2 } -Kind exact).result -cne 'FAIL') { throw 'An exact mismatch passed.' }
$diagnostic = @{ identifier = 'JBSA.TEST'; severity = 'warning'; operation = 'decode'; affected = $null; values = @{ offset = 42 }; message = 'wording' }
$otherDiagnostic = $diagnostic.Clone(); $otherDiagnostic.message = 'other wording'; $otherDiagnostic.exception_class = 'OtherException'
if ((Compare-ConformanceValue $diagnostic $otherDiagnostic diagnostic).result -cne 'PASS') { throw 'Diagnostic wording participated in equality.' }
$otherDiagnostic.values = @{ offset = 43 }
if ((Compare-ConformanceValue $diagnostic $otherDiagnostic diagnostic).result -cne 'FAIL') { throw 'Structured diagnostic values were excluded.' }
if ((Compare-ConformanceValue @{ identifier = 'JBSA.TEST' } @{ identifier = 'JBSA.TEST' } diagnostic).result -cne 'INVALID') { throw 'Incomplete diagnostics passed.' }
$entry = @{ decoded_name = 'a'; wire_name_bytes = '61'; normalized_name_identity = 'a'; wire_hashes = @(1); logical_size = 0; stored_size = 0; decoded_size = 0; compression_state = 'stored'; flags = 0; payload_sha256 = ('e' * 64) }
$semantic = @{ archive_family = 'tes3'; wire_version = 256; entry_count = 1; entries = @($entry); diagnostics = @(); physical_offset = 7 }
$otherSemantic = $semantic.Clone(); $otherSemantic.physical_offset = 13
if ((Compare-ConformanceValue $semantic $otherSemantic semantic).result -cne 'PASS') { throw 'Physical offsets participated in semantic equality.' }
$otherSemantic.entries = @($entry.Clone()); $otherSemantic.entries[0].payload_sha256 = ('f' * 64)
if ((Compare-ConformanceValue $semantic $otherSemantic semantic).result -cne 'FAIL') { throw 'Payload mismatch passed.' }
if ((Compare-ConformanceValue @{ archive_family = 'tes3' } @{ archive_family = 'tes3' } semantic).result -cne 'INVALID') { throw 'Incomplete semantic projection passed.' }
$cli = @{ exit_status = 0; stable_streams = @(); records = @('a', 'b'); published_artifacts = @(); residual_artifacts = @(); extracted_tree = @(); raw_stdout = 'elapsed 1s' }
$otherCli = $cli.Clone(); $otherCli.raw_stdout = 'elapsed 2s'
if ((Compare-ConformanceValue $cli $otherCli cli).result -cne 'PASS') { throw 'Raw CLI presentation participated in equality.' }
$otherCli.records = @('b', 'a')
if ((Compare-ConformanceValue $cli $otherCli cli).result -cne 'FAIL') { throw 'CLI record order mismatch passed.' }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) "jbsa-evidence-$([guid]::NewGuid().ToString('N'))"
try {
    $evidence = Add-ConformanceEvidence -Directory $testRoot -Bytes ([Text.Encoding]::UTF8.GetBytes('abc'))
    if ($evidence.sha256 -cne 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad') { throw 'Evidence has incorrect digest.' }
    $repeated = Add-ConformanceEvidence -Directory $testRoot -Bytes ([Text.Encoding]::UTF8.GetBytes('abc'))
    if ($evidence.path -cne $repeated.path) { throw 'Evidence address is unstable.' }
    [IO.File]::WriteAllText($evidence.path, 'tampered')
    $rejected = $false
    try { Add-ConformanceEvidence -Directory $testRoot -Bytes ([Text.Encoding]::UTF8.GetBytes('abc')) | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'An existing corrupt evidence object was accepted or overwritten.' }
    $caseId = 'CV1-tes3.decode.empty.stored.default'
    $catalog = @{ cases = @(@{ identity = @{ case_id = $caseId }; metadata = @{ assertions = @('semantic') } }) }
    $result = @{ case_id = $caseId; contract = 'conformance-v1'; case_manifest_sha256 = ('a' * 64); candidate_artifacts = @(); codec_profile_sha256 = $null; compatibility_profile = 'none'; fixture_digests = @(); golden_digests = @(); oracle = $null; validators = @(); environment = @{ os = 'test' }; start_time = '2026-09-05T00:00:00Z'; duration_ms = 1; result = 'INVALID'; assertions = @(@{ assertion_id = 'semantic'; applicability = 'REQUIRED'; result = 'INVALID'; expected = $null; observed = $null; evidence = @() }) }
    $report = Write-ConformanceReport -Catalog $catalog -Results @($result) -Directory $testRoot
    $firstMatrix = [IO.File]::ReadAllText($report.matrix_path)
    $result.duration_ms = 99; $result.start_time = '2026-09-05T00:00:01Z'
    Write-ConformanceReport -Catalog $catalog -Results @($result) -Directory $testRoot | Out-Null
    if ([IO.File]::ReadAllText($report.matrix_path) -cne $firstMatrix) { throw 'Non-gating timing changed the deterministic matrix.' }
    if ((Get-Content -Raw $report.report_path | ConvertFrom-Json).automated_conformance) { throw 'INVALID result established an automated claim.' }
    foreach ($badResults in @(@(), @($result, $result), @(@{ case_id = 'CV1-unknown' }))) {
        $rejected = $false
        try { Write-ConformanceReport -Catalog $catalog -Results $badResults -Directory $testRoot | Out-Null } catch { $rejected = $true }
        if (-not $rejected) { throw 'Missing, duplicate, or unknown case IDs were accepted.' }
    }
    $validEvidence = Add-ConformanceEvidence -Directory $testRoot -Bytes ([Text.Encoding]::UTF8.GetBytes('valid'))
    $result.result = 'PASS'; $result.codec_profile_sha256 = ('a' * 64)
    $result.candidate_artifacts = @($validEvidence); $result.fixture_digests = @($validEvidence.sha256); $result.golden_digests = @($validEvidence.sha256)
    $result.assertions[0].result = 'PASS'; $result.assertions[0].evidence = @($validEvidence)
    $passed = Write-ConformanceReport -Catalog $catalog -Results @($result) -Directory $testRoot
    if (-not $passed.automated_conformance) { throw 'A complete passing result did not establish its required-set claim.' }
    $result.assertions[0].evidence = @()
    $rejected = $false
    try { Write-ConformanceReport -Catalog $catalog -Results @($result) -Directory $testRoot | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'PASS was accepted without required evidence.' }
}
finally {
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing cleanup outside temp: $resolvedRoot" }
    if (Test-Path -LiteralPath $resolvedRoot) { Remove-Item -LiteralPath $resolvedRoot -Recurse -Force }
}
Write-Output 'Conformance evidence regression checks passed.'
