<# .SYNOPSIS
Deterministic conformance comparisons and content-addressed evidence storage.
#>
Set-StrictMode -Version Latest

function ConvertTo-ConformanceCanonicalJson {
    <# .SYNOPSIS
    Serializes structured observations with ordinal object keys, preserving array order and explicit nulls.
    #>
    param([AllowNull()] $Value)
    if ($null -eq $Value) { return 'null' }
    if ($Value -is [System.Collections.IDictionary] -or $Value -is [pscustomobject]) {
        $keys = [string[]]$(if ($Value -is [System.Collections.IDictionary]) { @($Value.Keys) } else { @($Value.PSObject.Properties.Name) })
        [Array]::Sort($keys, [StringComparer]::Ordinal)
        $members = foreach ($key in $keys) {
            $item = if ($Value -is [System.Collections.IDictionary]) { $Value[$key] } else { $Value.PSObject.Properties[$key].Value }
            (ConvertTo-Json -InputObject $key -Compress) + ':' + (ConvertTo-ConformanceCanonicalJson $item)
        }
        return '{' + ($members -join ',') + '}'
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $items = foreach ($item in $Value) { ConvertTo-ConformanceCanonicalJson $item }
        return '[' + ($items -join ',') + ']'
    }
    return ConvertTo-Json -InputObject $Value -Depth 100 -Compress
}

function Compare-ConformanceValue {
    <# .SYNOPSIS
    Compares expected and observed structured values; invalid projections cannot establish conformance.
    #>
    param([AllowNull()] $Expected, [AllowNull()] $Observed, [ValidateSet('exact', 'semantic', 'diagnostic', 'cli')] [string] $Kind = 'exact')
    try {
        $expectedProjection = Get-ConformanceProjection $Expected $Kind
        $observedProjection = Get-ConformanceProjection $Observed $Kind
        $expectedJson = ConvertTo-ConformanceCanonicalJson $expectedProjection
        $observedJson = ConvertTo-ConformanceCanonicalJson $observedProjection
        $matches = [string]::Equals($expectedJson, $observedJson, [StringComparison]::Ordinal)
        return [ordered]@{ result = $(if ($matches) { 'PASS' } else { 'FAIL' }); expected = $Expected; observed = $Observed; reason = $(if ($matches) { $null } else { 'Structured observations differ.' }) }
    }
    catch { return [ordered]@{ result = 'INVALID'; expected = $Expected; observed = $Observed; reason = $_.Exception.Message } }
}

function Assert-ConformanceFields {
    <# .SYNOPSIS
    Rejects omitted mandatory observation fields while permitting explicit null values.
    #>
    param([AllowNull()] $Value, [string[]] $Fields)
    if ($null -eq $Value -or ($Value -isnot [System.Collections.IDictionary] -and $Value -isnot [pscustomobject])) { throw 'A structured observation object is required.' }
    $keys = if ($Value -is [System.Collections.IDictionary]) { @($Value.Keys) } else { @($Value.PSObject.Properties.Name) }
    foreach ($field in $Fields) { if ($keys -cnotcontains $field) { throw "Observation is missing '$field'." } }
}

function Select-ConformanceFields {
    <# .SYNOPSIS
    Builds an explicit public observation projection so incidental presentation cannot become an authority.
    #>
    param($Value, [string[]] $Fields)
    Assert-ConformanceFields $Value $Fields
    $projection = [ordered]@{}
    foreach ($field in $Fields) { $projection[$field] = $Value.$field }
    return $projection
}

function Get-ConformanceProjection {
    <# .SYNOPSIS
    Validates and selects the complete family-specific semantic, diagnostic, or CLI observation contract.
    .DESCRIPTION
    Name absence is explicit null. Payload and reconstructed-DDS SHA-256 values identify retained exact bytes.
    Physical layout fields are omitted by construction; structured diagnostic values remain significant.
    #>
    param([AllowNull()] $Value, [string] $Kind)
    if ($Kind -eq 'exact') { return ,$Value }
    if ($Kind -eq 'diagnostic') {
        $diagnostic = Select-ConformanceFields $Value @('identifier', 'severity', 'operation', 'affected', 'values')
        foreach ($field in @('identifier', 'severity', 'operation')) { if ([string]::IsNullOrWhiteSpace($diagnostic[$field])) { throw "Diagnostic $field is required." } }
        return $diagnostic
    }
    if ($Kind -eq 'cli') {
        $cli = Select-ConformanceFields $Value @('exit_status', 'stable_streams', 'records', 'published_artifacts', 'residual_artifacts', 'extracted_tree')
        if ($cli.exit_status -isnot [int] -and $cli.exit_status -isnot [long]) { throw 'CLI exit_status must be an integer.' }
        foreach ($field in @('stable_streams', 'records', 'published_artifacts', 'residual_artifacts', 'extracted_tree')) {
            if ($cli[$field] -isnot [System.Collections.IList]) { throw "CLI $field must be an ordered array." }
        }
        foreach ($artifact in @($cli.published_artifacts) + @($cli.residual_artifacts) + @($cli.extracted_tree)) {
            Assert-ConformanceFields $artifact @('path', 'kind')
            if ($artifact.kind -cnotin @('file', 'directory', 'absent')) { throw 'Filesystem observation kind must be file, directory, or absent.' }
            if ($artifact.kind -ceq 'file') {
                Assert-ConformanceFields $artifact @('sha256')
                if ($artifact.sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Filesystem file bytes must be represented by SHA-256.' }
            }
        }
        return $cli
    }
    $projection = Select-ConformanceFields $Value @('archive_family', 'wire_version', 'entry_count', 'entries', 'diagnostics')
    $family = $Value.archive_family
    if ($family -cnotmatch '^(tes3|bsa-06[789]|fo4-(gnrl|dx10)-v[178]|sf-(gnrl|dx10)-v[23](-m3)?)$') { throw "Unknown semantic archive family '$family'." }
    if ($Value.entries -isnot [System.Collections.IList] -or $Value.diagnostics -isnot [System.Collections.IList]) { throw 'Semantic entries and diagnostics must be ordered arrays.' }
    if ($null -eq $Value.wire_version -or $null -eq $Value.entry_count -or $Value.entry_count -ne $Value.entries.Count) { throw 'Semantic wire version or entry_count is missing or inconsistent.' }
    $entryFields = @('decoded_name', 'wire_name_bytes', 'normalized_name_identity', 'wire_hashes', 'logical_size', 'stored_size', 'decoded_size', 'compression_state', 'flags', 'payload_sha256')
    if ($family -like 'bsa-*') {
        foreach ($field in @('archive_flags', 'file_flags', 'folder_order')) { Assert-ConformanceFields $Value @($field); $projection[$field] = $Value.$field }
        $entryFields += @('folder_hash', 'file_hash', 'embedded_name')
    }
    if ($family -match '-(gnrl|dx10)-') {
        foreach ($field in @('subtype', 'compression_method')) { Assert-ConformanceFields $Value @($field); $projection[$field] = $Value.$field }
        $entryFields += @('chunk_count', 'chunks')
        if ($family -match '-gnrl-') { $entryFields += 'extension' }
        else { $entryFields += @('width', 'height', 'dxgi_format', 'mip_count', 'cubemap', 'tile_mode', 'reconstructed_dds_sha256') }
    }
    $projection.entries = @(foreach ($entry in $Value.entries) {
        $selected = Select-ConformanceFields $entry $entryFields
        if ($null -eq $entry.decoded_name -or $null -eq $entry.wire_hashes -or $null -eq $entry.flags -or [string]::IsNullOrWhiteSpace($entry.compression_state)) { throw 'Semantic entry names, hashes, flags, and compression state must be present.' }
        foreach ($field in @('logical_size', 'stored_size', 'decoded_size')) {
            if ($null -eq $entry.$field -or $entry.$field -is [string] -or $entry.$field -lt 0 -or [decimal]$entry.$field -ne [decimal]::Truncate([decimal]$entry.$field)) { throw "Semantic entry $field must be a nonnegative integer." }
        }
        if ($entry.payload_sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Payload digest must be lowercase SHA-256.' }
        if ($family -match '-(gnrl|dx10)-') {
            if ($entry.chunks -isnot [System.Collections.IList] -or $entry.chunk_count -ne $entry.chunks.Count) { throw 'Chunk count must match the ordered chunk array.' }
            $chunkFields = @('stored_size', 'decoded_size', 'payload_sha256')
            if ($family -match '-dx10-') {
                $chunkFields += @('first_mip', 'last_mip')
                if ($entry.reconstructed_dds_sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Canonical reconstructed DDS digest must be lowercase SHA-256.' }
            }
            $selected.chunks = @(foreach ($chunk in $entry.chunks) {
                $selectedChunk = Select-ConformanceFields $chunk $chunkFields
                if ($chunk.payload_sha256 -cnotmatch '^[0-9a-f]{64}$') { throw 'Chunk payload digest must be lowercase SHA-256.' }
                $selectedChunk
            })
        }
        $selected
    })
    $projection.diagnostics = @(foreach ($diagnostic in $Value.diagnostics) { Get-ConformanceProjection $diagnostic diagnostic })
    return $projection
}

function Add-ConformanceEvidence {
    <# .SYNOPSIS
    Stores exact bytes under their SHA-256 address, never replacing an existing object.
    .DESCRIPTION
    Returns sha256 and absolute path. A corrupt existing object throws; concurrent identical writers are safe.
    #>
    param([Parameter(Mandatory)] [string] $Directory, [Parameter(Mandatory)] [AllowEmptyCollection()] [byte[]] $Bytes)
    $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($Bytes)).ToLowerInvariant()
    $objects = [IO.Path]::GetFullPath((Join-Path $Directory 'objects'))
    [IO.Directory]::CreateDirectory($objects) | Out-Null
    $path = Join-Path $objects "$digest.bin"
    if (-not [IO.File]::Exists($path)) {
        $temporary = Join-Path $objects ('.pending-' + [guid]::NewGuid().ToString('N'))
        try {
            $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            try { $stream.Write($Bytes, 0, $Bytes.Length) } finally { $stream.Dispose() }
            # Publish only closed complete bytes. Move without overwrite keeps competing writers immutable.
            [IO.File]::Move($temporary, $path, $false)
        }
        catch [IO.IOException] {
            # A competing writer may have created the same address; the digest check below remains authoritative.
            if (-not [IO.File]::Exists($path)) { throw }
        }
        finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
    }
    $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([IO.File]::ReadAllBytes($path))).ToLowerInvariant()
    if ($actual -cne $digest) { throw "Evidence object digest mismatch: $path" }
    return [ordered]@{ sha256 = $digest; path = $path }
}

function Write-ConformanceReport {
    <# .SYNOPSIS
    Validates an exact catalog result set and writes its stable matrix and full diagnostic report.
    .DESCRIPTION
    Cases and assertions follow ordinal case order and catalog assertion order. Timing is retained only in
    the full report. Missing context is permitted for non-PASS results, never for successful claims.
    #>
    param([Parameter(Mandatory)] $Catalog, [Parameter(Mandatory)] [AllowEmptyCollection()] [object[]] $Results, [Parameter(Mandatory)] [string] $Directory)
    $caseMap = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($case in $Catalog.cases) {
        $id = $case.identity.case_id
        if (-not $caseMap.TryAdd($id, $case)) { throw "Duplicate catalog case '$id'." }
    }
    $resultMap = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
    foreach ($result in $Results) {
        Assert-ConformanceFields $result @('case_id')
        $id = $result.case_id
        if (-not $caseMap.ContainsKey($id)) { throw "Unknown result case '$id'." }
        if (-not $resultMap.TryAdd($id, $result)) { throw "Duplicate result case '$id'." }
    }
    if ($resultMap.Count -ne $caseMap.Count) { throw 'Result set is missing required cases.' }
    if ($caseMap.Count -eq 0) { throw 'An empty catalog cannot establish conformance.' }
    $ids = [string[]]@($caseMap.Keys); [Array]::Sort($ids, [StringComparer]::Ordinal)
    $orderedResults = @(foreach ($id in $ids) {
        $result = $resultMap[$id]
        Assert-ConformanceFields $result @('contract', 'case_manifest_sha256', 'candidate_artifacts', 'codec_profile_sha256', 'compatibility_profile', 'fixture_digests', 'golden_digests', 'oracle', 'validators', 'environment', 'start_time', 'duration_ms', 'result', 'assertions')
        if ($result.contract -cne 'conformance-v1' -or $result.result -cnotin @('PASS', 'FAIL', 'INVALID', 'UNAVAILABLE')) { throw "Invalid contract or result for '$id'." }
        if ($result.assertions -isnot [System.Collections.IList]) { throw "Assertions must be an ordered array for '$id'." }
        if ($null -eq $result.environment -or [string]::IsNullOrWhiteSpace($result.start_time) -or $null -eq $result.duration_ms -or $result.duration_ms -lt 0) { throw "Missing environment or timing context for '$id'." }
        foreach ($field in @('case_manifest_sha256', 'codec_profile_sha256')) {
            if ($null -ne $result.$field -and $result.$field -cnotmatch '^[0-9a-f]{64}$') { throw "Invalid $field for '$id'." }
        }
        foreach ($field in @('fixture_digests', 'golden_digests', 'candidate_artifacts', 'validators')) {
            if ($result.$field -isnot [System.Collections.IList]) { throw "$field must be an array for '$id'." }
        }
        foreach ($digest in @($result.fixture_digests) + @($result.golden_digests)) {
            if ($digest -isnot [string] -or $digest -cnotmatch '^[0-9a-f]{64}$') { throw "Invalid fixture or golden digest for '$id'." }
        }
        foreach ($artifact in $result.candidate_artifacts) {
            Assert-ConformanceFields $artifact @('path', 'sha256')
            if ([string]::IsNullOrWhiteSpace($artifact.path) -or $artifact.sha256 -cnotmatch '^[0-9a-f]{64}$') { throw "Invalid candidate artifact identity for '$id'." }
        }
        if ($result.result -ceq 'PASS') {
            if ($null -eq $result.case_manifest_sha256 -or $null -eq $result.codec_profile_sha256 -or [string]::IsNullOrWhiteSpace($result.compatibility_profile)) { throw "Missing successful case identity for '$id'." }
            foreach ($field in @('candidate_artifacts', 'fixture_digests', 'golden_digests')) {
                if (@($result.$field).Count -eq 0) { throw "Missing $field for successful case '$id'." }
            }
        }
        $assertionMap = [Collections.Generic.Dictionary[string, object]]::new([StringComparer]::Ordinal)
        $expectedIds = [string[]]@($caseMap[$id].metadata.assertions)
        foreach ($assertion in $result.assertions) {
            Assert-ConformanceFields $assertion @('assertion_id', 'applicability', 'result', 'expected', 'observed', 'evidence')
            if ($expectedIds -cnotcontains $assertion.assertion_id -or -not $assertionMap.TryAdd($assertion.assertion_id, $assertion)) { throw "Unknown or duplicate assertion for '$id'." }
            if ($assertion.applicability -cne 'REQUIRED' -or $assertion.result -cnotin @('PASS', 'FAIL', 'INVALID', 'UNAVAILABLE')) { throw "Invalid assertion state for '$id'." }
            if ($result.result -ceq 'PASS' -and $assertion.result -cne 'PASS') { throw "Non-PASS assertion in successful case '$id'." }
            if ($assertion.result -ceq 'PASS' -and @($assertion.evidence).Count -eq 0) { throw "Successful assertion is missing evidence for '$id'." }
            foreach ($reference in $assertion.evidence) {
                Assert-ConformanceFields $reference @('sha256', 'path')
                if ($reference.sha256 -cnotmatch '^[0-9a-f]{64}$' -or -not [IO.File]::Exists($reference.path)) { throw "Invalid evidence reference for '$id'." }
                $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([IO.File]::ReadAllBytes($reference.path))).ToLowerInvariant()
                if ($digest -cne $reference.sha256) { throw "Evidence digest mismatch for '$id'." }
            }
        }
        if ($assertionMap.Count -ne $expectedIds.Count -or $expectedIds.Count -eq 0) { throw "Missing required assertions for '$id'." }
        # Reorder a copy: reporting must not mutate the runner's observation records or their original evidence.
        $copy = [ordered]@{}
        foreach ($key in $(if ($result -is [System.Collections.IDictionary]) { $result.Keys } else { $result.PSObject.Properties.Name })) { $copy[$key] = $result.$key }
        $copy.assertions = @($expectedIds | ForEach-Object { $assertionMap[$_] })
        $copy
    })
    $allPass = @($orderedResults | Where-Object result -cne 'PASS').Count -eq 0
    $matrix = [ordered]@{ contract = 'conformance-v1'; results = @($orderedResults | ForEach-Object { [ordered]@{ case_id = $_.case_id; result = $_.result } }) }
    $report = [ordered]@{ contract = 'conformance-v1'; automated_conformance = $allPass; results = $orderedResults }
    [IO.Directory]::CreateDirectory([IO.Path]::GetFullPath($Directory)) | Out-Null
    $matrixPath = [IO.Path]::GetFullPath((Join-Path $Directory 'matrix.json'))
    $reportPath = [IO.Path]::GetFullPath((Join-Path $Directory 'report.json'))
    [IO.File]::WriteAllText($matrixPath, (ConvertTo-ConformanceCanonicalJson $matrix) + "`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($reportPath, (ConvertTo-ConformanceCanonicalJson $report) + "`n", [Text.UTF8Encoding]::new($false))
    return [ordered]@{ matrix_path = $matrixPath; report_path = $reportPath; automated_conformance = $allPass }
}
