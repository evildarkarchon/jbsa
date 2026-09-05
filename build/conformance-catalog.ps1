<#
.SYNOPSIS
Read-only validation of immutable CV1 case identities and their content bindings.
.NOTES
Dot-source this module. Loading does not execute product adapters or create expected output.
#>

<#
.SYNOPSIS
Returns the lowercase SHA-256 of the exact file bytes, or throws for an unreadable file.
#>
function Get-ConformanceFileDigest {
    param([Parameter(Mandatory = $true)][string] $Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
}

<#
.SYNOPSIS
Requires a case-sensitive JSON property and returns its value, preserving an explicit null.
#>
function Get-ConformanceProperty {
    param([object] $Object, [string] $Name, [string] $Context)
    $property = @($Object.PSObject.Properties | Where-Object { $_.Name -ceq $Name })
    if ($property.Count -ne 1) { throw "$Context requires property '$Name'." }
    return ,$property[0].Value
}

<#
.SYNOPSIS
Reads one JSON object, rejecting duplicate member names before PowerShell can discard them.
.NOTES
Throws on malformed JSON, duplicate properties, non-object roots, or unreadable files.
#>
function Read-ConformanceJson {
    param([string] $Path)
    $text = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
    $document = [System.Text.Json.JsonDocument]::Parse($text)
    try {
        if ($document.RootElement.ValueKind -ne [System.Text.Json.JsonValueKind]::Object) {
            throw "Conformance JSON must contain one object: $Path"
        }
        Assert-ConformanceJsonMembers -Element $document.RootElement
    }
    finally { $document.Dispose() }
    return ($text | ConvertFrom-Json -Depth 100 -ErrorAction Stop)
}

<#
.SYNOPSIS
Rejects duplicate JSON member names recursively using ordinal comparison.
#>
function Assert-ConformanceJsonMembers {
    param([System.Text.Json.JsonElement] $Element)
    if ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Object) {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $names.Add($property.Name)) { throw "Duplicate JSON property '$($property.Name)'." }
            Assert-ConformanceJsonMembers -Element $property.Value
        }
    }
    elseif ($Element.ValueKind -eq [System.Text.Json.JsonValueKind]::Array) {
        foreach ($item in $Element.EnumerateArray()) { Assert-ConformanceJsonMembers -Element $item }
    }
}

<#
.SYNOPSIS
Validates and resolves one digest-bound repository-relative file without modifying its bytes.
.NOTES
Absolute paths and repository escapes are rejected. Missing bytes and mismatches invalidate loading.
#>
function Resolve-ConformanceBinding {
    param([object] $Binding, [string] $RepositoryRoot)
    $path = Get-ConformanceProperty $Binding 'path' 'Content binding'
    $sha256 = Get-ConformanceProperty $Binding 'sha256' 'Content binding'
    if ($path -isnot [string] -or [string]::IsNullOrWhiteSpace($path) -or [IO.Path]::IsPathRooted($path)) {
        throw 'A content binding requires a repository-relative path.'
    }
    if ($sha256 -isnot [string] -or $sha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw "Invalid SHA-256 binding for '$path'."
    }
    $root = [IO.Path]::GetFullPath($RepositoryRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $resolved = [IO.Path]::GetFullPath((Join-Path $root $path))
    if (-not $resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Content binding escapes the repository: '$path'."
    }
    $relative = [IO.Path]::GetRelativePath($root, $resolved).Replace('\', '/')
    if ($relative -match '^(?:TES5Edit(?:/|$)|tests/fixtures/local(?:/|$))') {
        throw "Conformance catalog binding is excluded from redistributable evidence: '$path'."
    }
    # Verify each existing path component before opening bytes; a junction must not redirect a
    # committed-evidence binding into a local oracle, proprietary corpus, or an external directory.
    $component = $resolved
    while ($component.Length -ge $root.Length) {
        if (Test-Path -LiteralPath $component) {
            $item = Get-Item -LiteralPath $component -Force -ErrorAction Stop
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Conformance catalog binding through a reparse point is excluded: '$path'."
            }
        }
        if ($component -ceq $root) { break }
        $component = [IO.Path]::GetDirectoryName($component)
    }
    if ((Get-ConformanceFileDigest -Path $resolved) -cne $sha256) {
        throw "Conformance content digest mismatch: '$path'."
    }
    return $resolved
}

<#
.SYNOPSIS
Loads a CV1 catalog only after identities, descriptions, digests, assertions, and coverage validate.
.PARAMETER Path
Catalog JSON to validate; disposable manifest copies can be checked without rebaselining.
.PARAMETER RepositoryRoot
Repository containing all committed content-addressed descriptors and corpus references.
.OUTPUTS
The validated manifest object. Cases retain their nested seven-field identity and separate metadata.
.NOTES
Throws on missing or duplicate identities, malformed bindings, unknown assertions, missing mandatory
base cells or targeted coverage, or a mutation to the independently pinned coverage contract.
#>
function Read-ConformanceCatalog {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $RepositoryRoot
    )
    $catalog = Read-ConformanceJson -Path $Path
    if ((Get-ConformanceProperty $catalog 'schema_version' 'Catalog') -ne 1 -or
        (Get-ConformanceProperty $catalog 'contract' 'Catalog') -cne 'conformance-v1') {
        throw 'Unsupported conformance catalog schema or contract.'
    }
    $specifications = Get-ConformanceProperty $catalog 'specification_set' 'Catalog'
    if (@($specifications).Count -eq 0) { throw 'Catalog must bind its governing specification set.' }
    $specificationPaths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($specification in $specifications) {
        if (-not $specificationPaths.Add($specification.path)) { throw 'Duplicate governing specification binding.' }
        $null = Resolve-ConformanceBinding $specification $RepositoryRoot
    }
    $expectedSpecificationPaths = @('docs/spec/requirements.yaml')
    $specificationRoot = Join-Path $RepositoryRoot 'docs/spec'
    foreach ($file in Get-ChildItem -LiteralPath $specificationRoot -Filter '*.md' -File -Recurse) {
        $expectedSpecificationPaths += [IO.Path]::GetRelativePath([IO.Path]::GetFullPath($RepositoryRoot), $file.FullName).Replace('\', '/')
    }
    if ($specificationPaths.Count -ne $expectedSpecificationPaths.Count) { throw 'Incomplete governing specification set.' }
    foreach ($path in $expectedSpecificationPaths) {
        if (-not $specificationPaths.Contains($path)) { throw "Missing governing specification binding: '$path'." }
    }
    $coverageBinding = Get-ConformanceProperty $catalog 'coverage_contract' 'Catalog'
    # An independent pin prevents deleting a coverage requirement together with its last case.
    if ($coverageBinding.sha256 -cne 'f9b050c0c5b87d0289369da690a4e0d0397dec7a91a9dc694102101790e4e564') {
        throw 'The mandatory coverage contract identity changed.'
    }
    $coverage = Read-ConformanceJson -Path (Resolve-ConformanceBinding $coverageBinding $RepositoryRoot)
    $tokens = Get-ConformanceProperty $catalog 'tokens' 'Catalog'
    $tokenMaps = @{}
    $configurations = @{}
    foreach ($field in @('archive_family', 'operation', 'fixture', 'codec', 'configuration')) {
        $map = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
        foreach ($entry in (Get-ConformanceProperty $tokens $field 'Token mappings')) {
            $token = Get-ConformanceProperty $entry 'token' "Token mapping $field"
            $description = Get-ConformanceProperty $entry 'description' "Token mapping $field"
            if ($token -isnot [string] -or $token -cnotmatch '^[a-z0-9]+(?:-[a-z0-9]+)*$' -or
                $description -isnot [string] -or [string]::IsNullOrWhiteSpace($description) -or $map.ContainsKey($token)) {
                throw "Invalid or duplicate token mapping in '$field'."
            }
            if ($field -in @('fixture', 'configuration')) {
                $boundPath = Resolve-ConformanceBinding $entry $RepositoryRoot
                if ($entry.path -cne "tests/conformance/objects/sha256/$($entry.sha256).json") {
                    throw "Descriptor must be addressed by its content digest: '$token'."
                }
                $descriptor = Read-ConformanceJson -Path $boundPath
                if ($descriptor.description -cne $description) { throw "Descriptor description mismatch: '$token'." }
                if ($field -eq 'fixture') {
                    if ($descriptor.token -cne $token) { throw "Descriptor token mismatch: '$token'." }
                    if (($entry.binding | ConvertTo-Json -Depth 100 -Compress) -cne
                        ($descriptor.binding | ConvertTo-Json -Depth 100 -Compress)) {
                        throw "Fixture binding differs from its immutable descriptor: '$token'."
                    }
                }
                else { $configurations[$token] = $descriptor }
            }
            $map.Add($token, $entry)
        }
        $tokenMaps[$field] = $map
    }
    $assertionIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $semanticAssertionIds = @('decode-semantic-projection', 'oracle-to-jbsa', 'jbsa-to-oracle')
    $requirementIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $requirementsText = Get-Content -LiteralPath (Join-Path $RepositoryRoot 'docs/spec/requirements.yaml') -Raw
    foreach ($match in [regex]::Matches($requirementsText, '(?m)^\s+- id: (JBSA-[A-Z0-9]+-[0-9]+)\s*$')) {
        $null = $requirementIds.Add($match.Groups[1].Value)
    }
    foreach ($assertion in (Get-ConformanceProperty $catalog 'assertions' 'Catalog')) {
        if ([string]::IsNullOrWhiteSpace($assertion.id) -or [string]::IsNullOrWhiteSpace($assertion.description) -or
            -not $assertionIds.Add($assertion.id) -or @($assertion.requirements).Count -eq 0) {
            throw 'Invalid or duplicate assertion definition.'
        }
        foreach ($requirement in $assertion.requirements) {
            if (-not $requirementIds.Contains($requirement)) { throw "Unknown owning requirement: '$requirement'." }
        }
        if ($assertion.id -cin $semanticAssertionIds -and
            (Get-ConformanceProperty $assertion 'kind' 'Semantic assertion definition') -cne 'semantic') {
            throw "Required semantic assertion cannot select another comparator: '$($assertion.id)'."
        }
    }
    foreach ($id in $semanticAssertionIds) {
        if (-not $assertionIds.Contains($id)) { throw "Missing required semantic assertion definition: '$id'." }
    }
    $requiredCoverage = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($item in $coverage.coverage_items) { $null = $requiredCoverage.Add($item.id) }
    $seenCoverage = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $caseIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $cells = [Collections.Generic.Dictionary[string,object]]::new([StringComparer]::Ordinal)
    $identityFields = @('case_id', 'contract', 'archive_family', 'operation', 'fixture', 'codec', 'configuration')
    foreach ($case in (Get-ConformanceProperty $catalog 'cases' 'Catalog')) {
        $identity = Get-ConformanceProperty $case 'identity' 'Case'
        $metadata = Get-ConformanceProperty $case 'metadata' 'Case'
        foreach ($field in @('classification', 'mode', 'expected_behavior', 'coverage_items', 'assertions',
            'fixture_binding', 'golden_bindings', 'evidence_required', 'adapter')) {
            $null = Get-ConformanceProperty $metadata $field 'Case metadata'
        }
        if (@($identity.PSObject.Properties).Count -ne 7) { throw 'Case identity requires exactly seven fields.' }
        foreach ($field in $identityFields) { $null = Get-ConformanceProperty $identity $field 'Case identity' }
        $expectedId = "CV1-$($identity.archive_family).$($identity.operation).$($identity.fixture).$($identity.codec).$($identity.configuration)"
        if ($identity.contract -cne 'conformance-v1' -or $identity.case_id -cne $expectedId -or -not $caseIds.Add($identity.case_id)) {
            throw "Invalid or duplicate case identity '$($identity.case_id)'."
        }
        foreach ($field in @('archive_family', 'operation', 'fixture', 'codec', 'configuration')) {
            if (-not $tokenMaps[$field].ContainsKey($identity.$field)) { throw "Unknown $field token in '$expectedId'." }
        }
        if ($metadata.classification -cne 'REQUIRED' -or $metadata.mode -cne 'hosted' -or
            $metadata.expected_behavior -cnotin @('accept', 'reject', 'assert-specified-outcome')) {
            throw "Invalid case classification or behavior: '$expectedId'."
        }
        if ($identity.archive_family -eq 'global' -and $identity.codec -ne 'none') {
            throw "A global operation scenario must not select an archive codec: '$expectedId'."
        }
        if ($identity.operation -eq 'encode' -and $identity.archive_family -like '*dx10*') {
            $configuration = $configurations[$identity.configuration]
            if ($configuration.DdsTarget -cnotin @('PC', 'XBOX') -or
                -not $configuration.description.Contains("DdsTarget=$($configuration.DdsTarget)")) {
                throw "DDS encode configuration does not bind its exact DdsTarget: '$expectedId'."
            }
        }
        if (@($metadata.assertions).Count -eq 0) { throw "Case has no assertions: '$expectedId'." }
        $seenAssertions = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($assertion in $metadata.assertions) {
            if (-not $assertionIds.Contains($assertion) -or -not $seenAssertions.Add($assertion)) { throw "Unknown or duplicate case assertion: '$assertion'." }
        }
        # The registered execution engine invokes the two directions independently; the grouping
        # assertion alone cannot stand in for either direction's semantic observation.
        if ($identity.operation -ceq 'encode' -and $metadata.expected_behavior -ceq 'accept') {
            foreach ($direction in @('oracle-to-jbsa', 'jbsa-to-oracle')) {
                if (-not $seenAssertions.Contains($direction)) { throw "Positive encode is missing '$direction': '$expectedId'." }
            }
        }
        foreach ($item in $metadata.coverage_items) {
            if (-not $requiredCoverage.Contains($item) -or -not $seenAssertions.Contains('coverage-' + $item)) {
                throw "Unknown or unasserted coverage item: '$item'."
            }
            $null = $seenCoverage.Add($item)
        }
        $fixture = $tokenMaps.fixture[$identity.fixture]
        if (($metadata.fixture_binding | ConvertTo-Json -Depth 100 -Compress) -cne
            ($fixture.binding | ConvertTo-Json -Depth 100 -Compress)) { throw "Case fixture binding mismatch: '$expectedId'." }
        $binding = $metadata.fixture_binding
        if ($binding.state -ceq 'available') {
            if (@($binding.files).Count -eq 0 -or $null -eq $binding.generator -or $null -eq $binding.provenance) {
                throw "Available fixture has incomplete evidence: '$expectedId'."
            }
            foreach ($file in $binding.files) { $null = Resolve-ConformanceBinding $file $RepositoryRoot }
            $null = Resolve-ConformanceBinding $binding.generator.descriptor $RepositoryRoot
            $null = Resolve-ConformanceBinding $binding.generator.implementation $RepositoryRoot
            $provenance = Read-ConformanceJson -Path (Resolve-ConformanceBinding $binding.provenance.manifest $RepositoryRoot)
            $sourceFixture = @($provenance.fixtures | Where-Object { $_.id -ceq $binding.provenance.fixture_id })
            if ($sourceFixture.Count -ne 1 -or $binding.files.Count -ne 1 -or
                $binding.files[0].sha256 -cne $sourceFixture[0].output.sha256 -or
                $binding.files[0].path -cne ('tests/fixtures/synthetic/' + $sourceFixture[0].output.path) -or
                $binding.generator.id -cne $provenance.generator.id -or
                $binding.generator.version -cne $provenance.generator.version -or
                $binding.generator.implementation.path -cne $provenance.generator.implementation -or
                $binding.generator.recipe_sha256 -cne $sourceFixture[0].input_sha256 -or
                ($binding.generator.configuration | ConvertTo-Json -Depth 100 -Compress) -cne
                ($sourceFixture[0].generation | ConvertTo-Json -Depth 100 -Compress)) {
                throw "Fixture generator or source provenance mismatch: '$expectedId'."
            }
            $expectedGoldens = @($provenance.goldens | Where-Object { $binding.provenance.fixture_id -cin $_.source_fixture_ids })
            if (@($metadata.golden_bindings).Count -ne $expectedGoldens.Count) {
                throw "Case golden set differs from source-fixture provenance: '$expectedId'."
            }
            foreach ($golden in $metadata.golden_bindings) {
                if (@($expectedGoldens | Where-Object {
                    $_.sha256 -ceq $golden.sha256 -and ('tests/fixtures/synthetic/' + $_.path) -ceq $golden.path
                }).Count -ne 1) { throw "Unbound golden source: '$expectedId'." }
            }
        }
        elseif ($binding.state -cne 'missing' -or [string]::IsNullOrWhiteSpace($binding.missing_reason)) {
            throw "Fixture availability is not explicit: '$expectedId'."
        }
        elseif (@($binding.files).Count -ne 0 -or @($metadata.golden_bindings).Count -ne 0) {
            throw "A missing fixture cannot carry available golden or input bytes: '$expectedId'."
        }
        foreach ($golden in $metadata.golden_bindings) { $null = Resolve-ConformanceBinding $golden $RepositoryRoot }
        if ($identity.fixture -ceq "base-$($identity.archive_family)-$($identity.codec)") {
            $key = "$($identity.archive_family).$($identity.operation).$($identity.codec)"
            if ($cells.ContainsKey($key)) { throw "Duplicate base matrix cell: '$key'." }
            $cells.Add($key, $case)
        }
    }
    foreach ($item in $requiredCoverage) {
        if (-not $seenCoverage.Contains($item)) { throw "Required targeted coverage is missing: '$item'." }
    }
    foreach ($family in $coverage.base_families) {
        foreach ($codec in $coverage.codec_universe) {
            foreach ($direction in @('decode', 'encode')) {
                $key = "$($family.token).$direction.$codec"
                if (-not $cells.ContainsKey($key)) { throw "Required base matrix cell is missing: '$key'." }
                $supported = $codec -cin $family.codecs -or ($family.token -ceq 'sf-gnrl-v3-m3' -and $codec -ceq 'stored') -or
                    ($family.token -like '*gnrl*' -and $codec -ceq 'mixed')
                $expectedBehavior = if ($family.token -like '*dx10*' -and $codec -cin @('stored', 'mixed')) {
                    if ($direction -ceq 'decode') { 'assert-specified-outcome' } else { 'reject' }
                }
                elseif ($supported -and ($direction -ceq 'decode' -or $family.encode)) { 'accept' }
                else { 'reject' }
                if ($cells[$key].metadata.expected_behavior -cne $expectedBehavior) {
                    throw "Base matrix applicability changed: '$key'."
                }
            }
        }
    }
    return $catalog
}
