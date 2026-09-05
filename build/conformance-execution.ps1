<# .SYNOPSIS Runs registered public-interface adapters against immutable expected observations. #>
. (Join-Path $PSScriptRoot 'conformance-evidence.ps1')
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')

function Assert-ConformanceBoundFile {
    <# .SYNOPSIS Resolves a digest-bound input and refuses changed bytes or filesystem indirections. #>
    param([Parameter(Mandatory)]$Binding, [Parameter(Mandatory)][string]$RepositoryRoot, [ValidateSet('Hosted', 'Local')][string]$Mode = 'Local')
    $path = [IO.Path]::GetFullPath($Binding.path, $RepositoryRoot)
    $localRoot = [IO.Path]::GetFullPath((Join-Path $RepositoryRoot 'tests/fixtures/local')).TrimEnd('\', '/')
    # Hosted denial precedes existence, attribute, and digest probes: local inputs must not be accessed at all.
    if ($Mode -eq 'Hosted' -and ($path.Equals($localRoot, [StringComparison]::OrdinalIgnoreCase) -or $path.StartsWith($localRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase))) { throw 'Hosted adapters cannot access local fixtures or oracle bytes.' }
    $item = Get-Item -LiteralPath $path -Force
    if ($item.PSIsContainer) { throw "Bound input is not a file: $path" }
    $cursor = $item
    while ($null -ne $cursor) {
        if ($cursor.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Bound input follows an indirection: $path" }
        $cursor = if ($cursor -is [IO.FileInfo]) { $cursor.Directory } else { $cursor.Parent }
    }
    if ($Binding.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
        (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Binding.sha256) {
        throw "Bound input digest mismatch: $path"
    }
    return $path
}

function Invoke-ConformanceRegisteredCase {
    <# .SYNOPSIS
    Executes a public adapter with a digest-bound request and compares only manifest-bound expectations.
    .DESCRIPTION
    The adapter receives -Request <JSON path> and emits {case_id, assertions:[{assertion_id, observed}]}.
    Nonzero adapter exit, unknown assertions, missing evidence, and changed inputs are INVALID. A
    conclusive candidate mismatch is FAIL; validator contradictions take precedence and are INVALID.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]$Case, [Parameter(Mandatory)]$Registration,
        [Parameter(Mandatory)][string]$RepositoryRoot, [Parameter(Mandatory)][string]$EvidenceDirectory,
        [ValidateSet('Hosted', 'Local')][string]$Mode = 'Hosted',
        [Parameter(Mandatory)][string]$ConfigurationSha256,
        [Parameter(Mandatory)][string]$SpecificationSha256,
        [AllowEmptyCollection()][object[]]$CandidateArtifacts = @(),
        [Parameter(Mandatory)][string]$CodecProfileSha256
    )
    $result = [ordered]@{ result = 'INVALID'; reason = $null; assertions = @(); oracle = $null; validators = @(); evidence = @() }
    try {
        if ($Registration.case_id -cne $Case.identity.case_id) { throw 'Adapter registration belongs to a different case.' }
        if ($Case.metadata.fixture_binding.state -cne 'available') { throw 'Fixture bytes are not available.' }
        $bindings = @($Case.metadata.fixture_binding.files) + @($Case.metadata.golden_bindings) + @($CandidateArtifacts) + @($Registration.command.inputs)
        foreach ($binding in $bindings) {
            $path = Assert-ConformanceBoundFile $binding $RepositoryRoot $Mode
        }
        $executable = Assert-ConformanceBoundFile @{ path = $Registration.command.executable; sha256 = $Registration.command.sha256 } $RepositoryRoot $Mode
        if ([IO.Path]::GetFileName($executable) -ieq 'BSArch.exe') { throw 'BSArch must use the dedicated local oracle adapter.' }
        if (@($Case.metadata.golden_bindings).Count -ne 1) { throw 'Exactly one bound assertion golden is required.' }
        $goldenPath = Assert-ConformanceBoundFile $Case.metadata.golden_bindings[0] $RepositoryRoot $Mode
        $golden = Get-Content -Raw -LiteralPath $goldenPath | ConvertFrom-Json -AsHashtable -Depth 100
        if ($golden.contract -cne 'conformance-v1' -or $golden.case_id -cne $Case.identity.case_id -or
            $golden.configuration_sha256 -cne $ConfigurationSha256 -or $golden.specification_sha256 -cne $SpecificationSha256) {
            throw 'Golden identity, specification, or configuration binding is stale or incomplete.'
        }
        $expected = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($assertion in $golden.assertions) {
            # A reviewed value still cannot weaken the owning assertion's mandatory semantic surface.
            if ($assertion.assertion_id -cin @('decode-semantic-projection', 'oracle-to-jbsa', 'jbsa-to-oracle') -and $assertion.kind -cne 'semantic') {
                throw 'Decode and differential assertions require complete semantic projections.'
            }
            if ($assertion.assertion_id -cnotin $Case.metadata.assertions -or -not $expected.TryAdd($assertion.assertion_id, $assertion)) { throw 'Unknown or duplicate golden assertion.' }
        }
        if ($expected.Count -ne $Case.metadata.assertions.Count) { throw 'Golden is missing case assertions.' }
        $positiveEncode = $Case.identity.operation -ceq 'encode' -and $Case.metadata.expected_behavior -ceq 'accept'
        if ($positiveEncode) {
            if ($Mode -ceq 'Local') {
                # UNAVAILABLE means no case execution: resolve the optional oracle before starting any candidate process.
                $readiness = Test-ConformanceOracleIdentity -RepositoryRoot $RepositoryRoot
                if ($readiness.result -cne 'PASS') { $result.result = $readiness.result; $result.reason = $readiness.error; $result.oracle = $readiness; return $result }
            }
            foreach ($direction in @('oracle-to-jbsa', 'jbsa-to-oracle')) {
                if (-not $expected.ContainsKey($direction) -or $expected[$direction].kind -cne 'semantic') { throw "Encode differential evidence is incomplete: $direction" }
            }
            if ($golden.oracle_sha256 -cne '4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2') { throw 'Encode differential requires a pinned oracle golden.' }
            $oracleArchive = Assert-ConformanceBoundFile $golden.oracle_archive $RepositoryRoot $Mode
            if (-not $golden.Contains('source_payloads') -or $golden.source_payloads -isnot [Collections.IList]) { throw 'Encode golden must bind its exact source payload tree.' }
        }
        [void][IO.Directory]::CreateDirectory($EvidenceDirectory)
        $work = Join-Path $EvidenceDirectory 'work'
        [void][IO.Directory]::CreateDirectory($work)
        $request = [ordered]@{
            contract = 'conformance-v1'; case = $Case; candidate_artifacts = $CandidateArtifacts
            codec_profile_sha256 = $CodecProfileSha256; configuration_sha256 = $ConfigurationSha256
            repository_root = $RepositoryRoot; mode = $Mode; phase = 'observe'
        }
        $requestPath = Join-Path $EvidenceDirectory 'request.json'
        [IO.File]::WriteAllText($requestPath, (ConvertTo-ConformanceCanonicalJson $request))
        $arguments = @($Registration.command.arguments) + @('-Request', $requestPath)
        $observation = Invoke-ConformanceProcess -Executable $executable -Arguments $arguments -WorkingDirectory $work -EvidenceDirectory (Join-Path $EvidenceDirectory 'streams')
        $result.evidence += $observation
        if ($observation.result -cne 'PASS' -or $observation.exit_status -ne 0) { throw 'Public adapter failed to produce a complete observation.' }
        $actual = Get-Content -Raw -LiteralPath $observation.stdout.path | ConvertFrom-Json -AsHashtable -Depth 100
        if ($actual.case_id -cne $Case.identity.case_id) { throw 'Observed case identity does not match the request.' }
        $observed = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($assertion in $actual.assertions) {
            if (-not $expected.ContainsKey($assertion.assertion_id) -or -not $observed.TryAdd($assertion.assertion_id, $assertion)) { throw 'Unknown or duplicate observed assertion.' }
            if ($positiveEncode -and $assertion.assertion_id -ceq 'oracle-to-jbsa') { throw 'Oracle-to-JBSA evidence must come from the separate decode invocation.' }
        }
        if ($positiveEncode) {
            $decodeWork = Join-Path $EvidenceDirectory 'oracle-to-jbsa-work'
            [void][IO.Directory]::CreateDirectory($decodeWork)
            $decodeRequest = [ordered]@{} + $request
            $decodeRequest.phase = 'oracle-to-jbsa'
            $decodeRequest.input_archive = @{ path = $oracleArchive; sha256 = $golden.oracle_archive.sha256 }
            $decodeRequestPath = Join-Path $EvidenceDirectory 'oracle-to-jbsa-request.json'
            [IO.File]::WriteAllText($decodeRequestPath, (ConvertTo-ConformanceCanonicalJson $decodeRequest))
            $decodeObservation = Invoke-ConformanceProcess -Executable $executable -Arguments (@($Registration.command.arguments) + @('-Request', $decodeRequestPath)) -WorkingDirectory $decodeWork -EvidenceDirectory (Join-Path $EvidenceDirectory 'oracle-to-jbsa-streams')
            $result.evidence += $decodeObservation
            if ($decodeObservation.result -cne 'PASS' -or $decodeObservation.exit_status -ne 0) { throw 'Oracle-to-JBSA decode process did not complete.' }
            $decoded = Get-Content -Raw -LiteralPath $decodeObservation.stdout.path | ConvertFrom-Json -AsHashtable -Depth 100
            if ($decoded.case_id -cne $Case.identity.case_id -or @($decoded.assertions).Count -ne 1 -or $decoded.assertions[0].assertion_id -cne 'oracle-to-jbsa') { throw 'Separate decode must emit exactly the oracle-to-jbsa assertion for this case.' }
            $observed.Add('oracle-to-jbsa', $decoded.assertions[0])
            $decodedFiles = @($decodeObservation.filesystem_after | Where-Object kind -eq 'file')
            if ((Compare-ConformanceValue -Expected $golden.source_payloads -Observed $decodedFiles -Kind exact).result -cne 'PASS') { throw 'JBSA decode of the oracle archive did not preserve exact source payload files.' }
            [void](Assert-ConformanceBoundFile $golden.oracle_archive $RepositoryRoot $Mode)
        }
        if ($observed.Count -ne $expected.Count) { throw 'Observed assertions are incomplete.' }
        foreach ($id in $Case.metadata.assertions) {
            $comparison = Compare-ConformanceValue -Expected $expected[$id].expected -Observed $observed[$id].observed -Kind $expected[$id].kind
            $assertionObservation = if ($positiveEncode -and $id -ceq 'oracle-to-jbsa') { $decodeObservation } else { $observation }
            $result.assertions += [ordered]@{ assertion_id = $id; applicability = 'REQUIRED'; result = $comparison.result; expected = $comparison.expected; observed = $comparison.observed; evidence = @($assertionObservation.stdout, $assertionObservation.stderr) + @($assertionObservation.filesystem_evidence) + @($assertionObservation.artifact_evidence); reason = $comparison.reason }
        }
        $result.result = if (@($result.assertions | Where-Object { $_.result -eq 'INVALID' }).Count) { 'INVALID' } elseif (@($result.assertions | Where-Object { $_.result -eq 'FAIL' }).Count) { 'FAIL' } else { 'PASS' }
        # A successful self observation never substitutes for independent archive corroboration.
        if ($Case.identity.archive_family -cne 'global' -and $Case.metadata.expected_behavior -ceq 'accept') {
            if (@($Registration.validators | Where-Object { $_.tool.kind -ceq 'archive' }).Count -eq 0) { throw 'An Independent Validator is required for this Archive Family.' }
            if ($Case.identity.archive_family -like '*dx10*' -and @($Registration.validators | Where-Object { $_.tool.kind -ceq 'dds' }).Count -eq 0) { throw 'DirectXTex is required for reconstructed DDS evidence.' }
            foreach ($validator in $Registration.validators) {
                $artifact = @($actual.artifacts | Where-Object { $_.role -ceq $validator.input_role })
                if ($artifact.Count -ne 1) { throw 'Validator input artifact is missing or ambiguous.' }
                $inputPath = Assert-ConformanceBoundFile $artifact[0] $work
                if (-not $inputPath.StartsWith($work + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Validator input must be an adapter-owned output.' }
                $assertionId = $validator.assertion_id
                if (-not $expected.ContainsKey($assertionId)) { throw 'Validator lacks an authoritative expected projection.' }
                $toolPath = Assert-ConformanceBoundFile @{ path = $validator.tool.path; sha256 = $validator.tool.sha256 } $RepositoryRoot $Mode
                $toolRecord = @{} + $validator.tool
                $toolRecord.path = $toolPath
                $validatorArguments = @($validator.arguments | ForEach-Object { if ($_ -ceq '{input}') { $inputPath } else { $_ } })
                $validation = Invoke-ConformanceValidator -Tool $toolRecord -InputPath $inputPath -InputSha256 $artifact[0].sha256 -ExpectedProjection $expected[$assertionId].expected -Arguments $validatorArguments -WorkingDirectory $work -EvidenceDirectory (Join-Path $EvidenceDirectory ('validator-' + $result.validators.Count))
                $result.validators += $validation
                if ($validation.result -cne 'PASS') { throw 'Independent Validator disagreement or incomplete evidence.' }
            }
            if ($Case.identity.operation -ceq 'encode') {
                $oracleInput = @($actual.artifacts | Where-Object { $_.role -ceq $Registration.oracle_input_role })
                if ($oracleInput.Count -ne 1) { throw 'Oracle decode input archive is missing or ambiguous.' }
                $oracleInputPath = Assert-ConformanceBoundFile $oracleInput[0] $work
                if (-not $oracleInputPath.StartsWith($work + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Oracle input must be an adapter-owned candidate archive.' }
                if ($Mode -ceq 'Hosted') {
                    # An archived oracle observation proves only the exact candidate bytes it actually decoded.
                    if ($golden.oracle_candidate_sha256 -cne $oracleInput[0].sha256 -or $golden.oracle_candidate_result -cne 'PASS') { throw 'Hosted encode requires committed successful oracle decode evidence bound to the exact candidate archive.' }
                    $result.oracle = @{ source = 'committed-golden'; sha256 = $golden.oracle_sha256; input_sha256 = $golden.oracle_candidate_sha256; result = $golden.oracle_candidate_result }
                }
                else {
                    if (-not $Registration.Contains('oracle_arguments')) { throw 'Local encode differential lacks an oracle invocation.' }
                    if ($Registration.oracle_arguments -cnotcontains '{input}' -or $Registration.oracle_arguments -cnotcontains '{output}') { throw 'Oracle decode invocation must bind the candidate archive and fresh output directory.' }
                    $oracleWork = Join-Path $EvidenceDirectory 'oracle-work'
                    [void][IO.Directory]::CreateDirectory($oracleWork)
                    $oracleArguments = @($Registration.oracle_arguments | ForEach-Object { if ($_ -ceq '{input}') { $oracleInputPath } elseif ($_ -ceq '{output}') { $oracleWork } else { $_ } })
                    $result.oracle = Invoke-ConformanceOracle -RepositoryRoot $RepositoryRoot -Arguments $oracleArguments -WorkingDirectory $oracleWork -EvidenceDirectory (Join-Path $EvidenceDirectory 'oracle')
                    if ($result.oracle.result -cne 'PASS') { $result.result = $result.oracle.result; $result.reason = $result.oracle.error; return $result }
                    if ($result.oracle.exit_status -ne 0) { throw 'Oracle rejected candidate output.' }
                    $oracleFiles = @($result.oracle.filesystem_after | Where-Object kind -eq 'file')
                    if ((Compare-ConformanceValue -Expected $golden.source_payloads -Observed $oracleFiles -Kind exact).result -cne 'PASS') { throw 'Oracle decode of JBSA output did not preserve exact source payload files.' }
                    [void](Assert-ConformanceBoundFile $oracleInput[0] $work)
                }
            }
        }
        foreach ($binding in $bindings) { [void](Assert-ConformanceBoundFile $binding $RepositoryRoot $Mode) }
        $result.reason = 'Every applicable golden comparison and required adapter evidence was evaluated.'
    }
    catch { $result.result = 'INVALID'; $result.reason = $_.Exception.Message }
    return $result
}
