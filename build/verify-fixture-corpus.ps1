<#
.SYNOPSIS
Verifies the committed synthetic fixture corpus without modifying it.

.PARAMETER CorpusRoot
Exact root containing manifest.json, generator.json, schemas, artifacts, and content-addressed
goldens.

.NOTES
This audit enforces JBSA-LIC-004 through JBSA-LIC-006 and the fixture/golden integrity controls
needed by conformance-v1. It never generates, rebaselines, replaces, or approves fixture bytes.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string] $CorpusRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$sha256Pattern = '^[0-9a-f]{64}$'
$referenceSnapshotRevision = 'fd1e36020b2b5b6217e553dc0038983146a2e2dd'
$oracleSha256 = '4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2'
$requiredCoverage = @(
    'structural',
    'boundary',
    'malformed',
    'compression',
    'name-encoding',
    'ordering',
    'overlay',
    'split',
    'resource-limit',
    'fo4-gnrl-v7',
    'sf-gnrl-v3-m3'
)
$requiredIndependentFixtureIds = @(
    'fo4-gnrl-v7-stored',
    'fo4-gnrl-v7-zlib',
    'sf-gnrl-v3-m3-raw-lz4',
    'sf-gnrl-v3-m3-mixed'
)

<#
.SYNOPSIS
Reads one required JSON document at a bounded parser depth.

.PARAMETER Path
Exact JSON document path.

.PARAMETER Description
Stable document name included in validation failures.

.OUTPUTS
The parsed JSON object.

.NOTES
Throws a terminating error when the document is absent, malformed, or not a JSON object.
#>
function Read-FixtureJson {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [string] $Description
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing $Description`: $Path"
    }
    try {
        $document = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json -Depth 100
    }
    catch {
        throw "Malformed $Description at ${Path}: $($_.Exception.Message)"
    }
    if ($null -eq $document -or $document -is [System.Array] -or
        $document -is [string] -or $document.GetType().IsPrimitive) {
        throw "$Description must contain one JSON object: $Path"
    }
    return $document
}

<#
.SYNOPSIS
Returns a required property descriptor without treating an allowed JSON null as absence.

.PARAMETER Object
JSON object that must own the property.

.PARAMETER Property
Exact case-sensitive property name.

.PARAMETER Context
Stable object identity included in validation failures.

.OUTPUTS
The PowerShell property descriptor, whose Value may be null.

.NOTES
Throws a terminating error when the property is absent or uses different casing.
#>
function Get-RequiredJsonProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Object,
        [Parameter(Mandatory = $true)]
        [string] $Property,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $propertyDescriptor = $Object.PSObject.Properties |
        Where-Object { $_.Name -ceq $Property } |
        Select-Object -First 1
    if ($null -eq $propertyDescriptor) {
        throw "$Context must declare $Property."
    }
    return $propertyDescriptor
}

<#
.SYNOPSIS
Requires one non-empty string property and returns its trimmed value.

.PARAMETER Object
JSON object that must own the property.

.PARAMETER Property
Exact property name.

.PARAMETER Context
Stable object identity included in validation failures.

.OUTPUTS
The validated, trimmed string.

.NOTES
Throws a terminating error for an absent, non-string, or blank value.
#>
function Assert-StringProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Object,
        [Parameter(Mandatory = $true)]
        [string] $Property,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $value = (Get-RequiredJsonProperty $Object $Property $Context).Value
    if ($value -isnot [string] -or [string]::IsNullOrWhiteSpace($value)) {
        throw "$Context must declare a non-empty string $Property."
    }
    return $value.Trim()
}

<#
.SYNOPSIS
Requires one non-empty array of non-empty strings.

.PARAMETER Object
JSON object that must own the array.

.PARAMETER Property
Exact property name.

.PARAMETER Context
Stable object identity included in validation failures.

.OUTPUTS
The validated string array.

.NOTES
Throws a terminating error for a scalar, empty array, or blank array member.
#>
function Assert-StringArrayProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Object,
        [Parameter(Mandatory = $true)]
        [string] $Property,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $value = (Get-RequiredJsonProperty $Object $Property $Context).Value
    if ($value -isnot [System.Array]) {
        throw "$Context must declare $Property as an array."
    }
    $values = @($value)
    if ($values.Count -eq 0 -or @($values | Where-Object {
                $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_)
            }).Count -ne 0) {
        throw "$Context must declare one or more non-empty $Property values."
    }
    return @($values)
}

<#
.SYNOPSIS
Requires one lowercase SHA-256 property and returns it.

.PARAMETER Object
JSON object that must own the digest.

.PARAMETER Property
Exact digest property name.

.PARAMETER Context
Stable object identity included in validation failures.

.OUTPUTS
The validated lowercase digest.

.NOTES
Throws a terminating error for any non-canonical SHA-256 representation.
#>
function Assert-Sha256Property {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Object,
        [Parameter(Mandatory = $true)]
        [string] $Property,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $digest = Assert-StringProperty $Object $Property $Context
    if ($digest -cnotmatch $sha256Pattern) {
        throw "$Context has an invalid lowercase SHA-256 in $Property."
    }
    return $digest
}

<#
.SYNOPSIS
Requires a string-valued JSON object that records exact command configuration.

.PARAMETER Object
JSON object that must own the configuration.

.PARAMETER Property
Exact configuration property name.

.PARAMETER Context
Stable object identity included in validation failures.

.OUTPUTS
The validated configuration object.

.NOTES
An empty object is valid because it precisely records that no options were used.
#>
function Assert-ConfigurationProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Object,
        [Parameter(Mandatory = $true)]
        [string] $Property,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $value = (Get-RequiredJsonProperty $Object $Property $Context).Value
    $isJsonObject = $null -ne $value -and $value -isnot [System.Array] -and
        $value -isnot [string] -and -not $value.GetType().IsPrimitive
    if (-not $isJsonObject) {
        throw "$Context must declare $Property as a JSON object."
    }
    foreach ($entry in $value.PSObject.Properties) {
        if ($entry.Value -isnot [string]) {
            throw "$Context $Property must map string keys to string values."
        }
    }
    return $value
}

<#
.SYNOPSIS
Tests whether two string-valued configuration maps are exactly equal.

.PARAMETER First
First validated configuration object.

.PARAMETER Second
Second validated configuration object.

.OUTPUTS
True when both maps contain the same case-sensitive keys and values; otherwise false.

.NOTES
Object property order is intentionally ignored because JSON object ordering is not semantic.
#>
function Test-ConfigurationMap {
    param(
        [Parameter(Mandatory = $true)]
        [object] $First,
        [Parameter(Mandatory = $true)]
        [object] $Second
    )

    $firstProperties = @($First.PSObject.Properties)
    $secondProperties = @($Second.PSObject.Properties)
    if ($firstProperties.Count -ne $secondProperties.Count) {
        return $false
    }
    foreach ($entry in $firstProperties) {
        $matchingEntry = $secondProperties |
            Where-Object { $_.Name -ceq $entry.Name } |
            Select-Object -First 1
        if ($null -eq $matchingEntry -or $matchingEntry.Value -cne $entry.Value) {
            return $false
        }
    }
    return $true
}

<#
.SYNOPSIS
Resolves a manifest path while proving it remains beneath the corpus root.

.PARAMETER Root
Canonical corpus root.

.PARAMETER RelativePath
Forward-slash relative path recorded in corpus metadata.

.PARAMETER Context
Stable record identity included in validation failures.

.OUTPUTS
The canonical absolute path.

.NOTES
Rooted, backslash, traversal, and corpus-root aliases are rejected before any file access.
#>
function Resolve-CorpusPath {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,
        [Parameter(Mandatory = $true)]
        [string] $RelativePath,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $segments = $RelativePath.Split('/')
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or
        [System.IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath.Contains('\') -or
        $RelativePath -cnotmatch '^[a-z0-9][a-z0-9./-]*$' -or
        @($segments | Where-Object { $_ -ceq '' -or $_ -ceq '.' -or $_ -ceq '..' }).Count -ne 0) {
        throw "$Context must use a non-traversing, forward-slash relative path: $RelativePath"
    }
    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $Root $RelativePath))
    $rootPrefix = $Root.TrimEnd(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    ) + [System.IO.Path]::DirectorySeparatorChar
    # The prefix check protects future callers if a path parser normalizes an unexpected alias.
    if (-not $fullPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Context resolves outside the fixture corpus: $RelativePath"
    }
    return $fullPath
}

<#
.SYNOPSIS
Verifies an accounted corpus object is a regular file with the recorded digest.

.PARAMETER Root
Canonical corpus root.

.PARAMETER RelativePath
Manifest-relative object path.

.PARAMETER ExpectedSha256
Canonical lowercase SHA-256 recorded by the manifest.

.PARAMETER Context
Stable record identity included in validation failures.

.OUTPUTS
The normalized manifest-relative path.

.NOTES
Reparse points are rejected because fixture identity must bind ordinary bytes, not an external
filesystem target.
#>
function Assert-CorpusObject {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,
        [Parameter(Mandatory = $true)]
        [string] $RelativePath,
        [Parameter(Mandatory = $true)]
        [string] $ExpectedSha256,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $fullPath = Resolve-CorpusPath $Root $RelativePath $Context
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        throw "$Context names a missing fixture object: $RelativePath"
    }
    $item = Get-Item -Force -LiteralPath $fullPath
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Context names a reparse point instead of a regular fixture object: $RelativePath"
    }
    $actualSha256 = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -cne $ExpectedSha256) {
        throw "$Context checksum mismatch for $RelativePath`: expected $ExpectedSha256, found $actualSha256."
    }
    return $RelativePath.Replace('\', '/')
}

<#
.SYNOPSIS
Requires a JSON Schema object to declare exact required properties.

.PARAMETER Schema
JSON Schema node expected to describe an object.

.PARAMETER RequiredProperties
Exact case-sensitive property names that must appear in required and properties.

.PARAMETER Context
Stable schema node identity included in validation failures.

.OUTPUTS
None.

.NOTES
This checks the committed contract itself; instance validation remains explicit in this audit so
the repository does not acquire a schema-validator runtime dependency.
#>
function Assert-SchemaRequiredPropertySet {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Schema,
        [Parameter(Mandatory = $true)]
        [string[]] $RequiredProperties,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    if ((Assert-StringProperty $Schema 'type' $Context) -cne 'object') {
        throw "$Context must declare type object."
    }
    $required = (Get-RequiredJsonProperty $Schema 'required' $Context).Value
    if ($required -isnot [System.Array]) {
        throw "$Context must declare required as an array."
    }
    $properties = (Get-RequiredJsonProperty $Schema 'properties' $Context).Value
    if ($null -eq $properties -or $properties -is [System.Array] -or
        $properties -is [string] -or $properties.GetType().IsPrimitive) {
        throw "$Context must declare properties as an object."
    }
    $additionalProperties = (Get-RequiredJsonProperty `
            $Schema 'additionalProperties' $Context).Value
    if ($additionalProperties -isnot [bool] -or $additionalProperties) {
        throw "$Context must reject undeclared properties."
    }
    foreach ($property in $RequiredProperties) {
        if (@($required) -cnotcontains $property) {
            throw "$Context must list $property as required."
        }
        [void](Get-RequiredJsonProperty $properties $property "$Context properties")
    }
}

<#
.SYNOPSIS
Returns the items schema from a required array property.

.PARAMETER Schema
JSON Schema object owning the array property.

.PARAMETER Property
Required array property name.

.PARAMETER Context
Stable schema node identity included in validation failures.

.OUTPUTS
The array's items schema.

.NOTES
Throws a terminating error when the array or its item declaration is incomplete.
#>
function Get-SchemaArrayItem {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Schema,
        [Parameter(Mandatory = $true)]
        [string] $Property,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $properties = (Get-RequiredJsonProperty $Schema 'properties' $Context).Value
    $arraySchema = (Get-RequiredJsonProperty $properties $Property "$Context properties").Value
    if ((Assert-StringProperty $arraySchema 'type' "$Context.$Property") -cne 'array') {
        throw "$Context.$Property must declare type array."
    }
    return (Get-RequiredJsonProperty $arraySchema 'items' "$Context.$Property").Value
}

<#
.SYNOPSIS
Returns one required nested property schema.

.PARAMETER Schema
JSON Schema object owning the nested property.

.PARAMETER Property
Required nested property name.

.PARAMETER Context
Stable schema node identity included in validation failures.

.OUTPUTS
The nested property schema.

.NOTES
The caller separately validates the nested node's type and required property set.
#>
function Get-SchemaProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Schema,
        [Parameter(Mandatory = $true)]
        [string] $Property,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $properties = (Get-RequiredJsonProperty $Schema 'properties' $Context).Value
    return (Get-RequiredJsonProperty $properties $Property "$Context properties").Value
}

<#
.SYNOPSIS
Returns one named object from a JSON Schema $defs map.

.PARAMETER Schema
Root JSON Schema that owns $defs.

.PARAMETER Definition
Exact definition name.

.PARAMETER Context
Stable schema identity included in validation failures.

.OUTPUTS
The named definition schema.

.NOTES
Throws a terminating error when the schema does not expose the requested local definition.
#>
function Get-SchemaDefinition {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Schema,
        [Parameter(Mandatory = $true)]
        [string] $Definition,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $definitions = (Get-RequiredJsonProperty $Schema '$defs' $Context).Value
    return (Get-RequiredJsonProperty $definitions $Definition "$Context definitions").Value
}

<#
.SYNOPSIS
Requires a schema node to reference one exact local definition.

.PARAMETER SchemaNode
Schema node expected to contain a $ref.

.PARAMETER Definition
Exact definition name beneath $defs.

.PARAMETER Context
Stable schema node identity included in validation failures.

.OUTPUTS
None.

.NOTES
The reference is checked in addition to the target definition so an unused complete definition
cannot disguise an incomplete instance contract.
#>
function Assert-SchemaDefinitionReference {
    param(
        [Parameter(Mandatory = $true)]
        [object] $SchemaNode,
        [Parameter(Mandatory = $true)]
        [string] $Definition,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $expectedReference = '#/$defs/{0}' -f $Definition
    $actualReference = Assert-StringProperty $SchemaNode '$ref' $Context
    if ($actualReference -cne $expectedReference) {
        throw "$Context must reference $expectedReference."
    }
}

<#
.SYNOPSIS
Requires one schema node to declare an exact constant.

.PARAMETER SchemaNode
Schema node expected to contain const.

.PARAMETER Expected
Required string or integer constant.

.PARAMETER Context
Stable schema node identity included in validation failures.

.OUTPUTS
None.

.NOTES
Integer constants must remain JSON numbers; a numeric-looking string is not accepted.
#>
function Assert-SchemaConstant {
    param(
        [Parameter(Mandatory = $true)]
        [object] $SchemaNode,
        [Parameter(Mandatory = $true)]
        [object] $Expected,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $actual = (Get-RequiredJsonProperty $SchemaNode 'const' $Context).Value
    if ($Expected -is [int] -or $Expected -is [long]) {
        if ($actual -isnot [long] -or $actual -ne $Expected) {
            throw "$Context must declare numeric const $Expected."
        }
    } elseif ($actual -isnot [string] -or $actual -cne $Expected) {
        throw "$Context must declare const $Expected."
    }
}

<#
.SYNOPSIS
Requires an oracle-digest schema to allow only null or the pinned digest.

.PARAMETER SchemaNode
Schema node expected to contain exactly two anyOf alternatives.

.PARAMETER Context
Stable schema node identity included in validation failures.

.OUTPUTS
None.

.NOTES
The nullable alternative records that no oracle was used; every non-null identity must remain the
single digest-pinned Conformance Oracle.
#>
function Assert-NullableOracleSchema {
    param(
        [Parameter(Mandatory = $true)]
        [object] $SchemaNode,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $alternatives = (Get-RequiredJsonProperty $SchemaNode 'anyOf' $Context).Value
    if ($alternatives -isnot [System.Array] -or @($alternatives).Count -ne 2) {
        throw "$Context must declare exactly null and the pinned oracle digest alternatives."
    }
    $hasNull = $false
    $hasOracle = $false
    foreach ($alternative in @($alternatives)) {
        $typeProperty = $alternative.PSObject.Properties |
            Where-Object { $_.Name -ceq 'type' } |
            Select-Object -First 1
        $constProperty = $alternative.PSObject.Properties |
            Where-Object { $_.Name -ceq 'const' } |
            Select-Object -First 1
        if ($null -ne $typeProperty -and $typeProperty.Value -ceq 'null') {
            $hasNull = $true
        }
        if ($null -ne $constProperty -and $constProperty.Value -ceq $oracleSha256) {
            $hasOracle = $true
        }
    }
    if (-not $hasNull -or -not $hasOracle) {
        throw "$Context must allow only null or exact Conformance Oracle digest $oracleSha256."
    }
}

<#
.SYNOPSIS
Verifies all three committed fixture metadata schemas declare the normative fields.

.PARAMETER FixtureManifestSchema
Parsed fixture-manifest.schema.json document.

.PARAMETER GeneratorSchema
Parsed generator.schema.json document.

.PARAMETER RebaselineSchema
Parsed rebaseline-record.schema.json document.

.OUTPUTS
None.

.NOTES
Rebaseline declarations are audited even when no rebaseline record is present, preserving the
separate deliberate-operation contract before the first rebaseline is needed. Throws a terminating
error when any schema weakens the required fixture, golden, or rebaseline contract.
#>
function Test-FixtureSchemaContract {
    param(
        [Parameter(Mandatory = $true)]
        [object] $FixtureManifestSchema,
        [Parameter(Mandatory = $true)]
        [object] $GeneratorSchema,
        [Parameter(Mandatory = $true)]
        [object] $RebaselineSchema
    )

    Assert-SchemaRequiredPropertySet $FixtureManifestSchema @(
        'schema_version', 'corpus_id', 'generated_on', 'reference_snapshot_revision',
        'generator', 'fixtures', 'goldens'
    ) 'fixture-manifest schema'
    Assert-SchemaConstant `
        (Get-SchemaProperty $FixtureManifestSchema 'schema_version' 'fixture-manifest schema') `
        1 'fixture-manifest schema_version'
    $generatedOnSchema = Get-SchemaProperty `
        $FixtureManifestSchema 'generated_on' 'fixture-manifest schema'
    if ((Assert-StringProperty $generatedOnSchema 'format' 'fixture-manifest generated_on') -cne 'date') {
        throw 'fixture-manifest generated_on must declare date format.'
    }
    $referenceRevisionSchema = Get-SchemaDefinition `
        $FixtureManifestSchema 'referenceRevision' 'fixture-manifest schema'
    Assert-SchemaConstant `
        $referenceRevisionSchema $referenceSnapshotRevision 'fixture-manifest Reference Snapshot'
    Assert-SchemaDefinitionReference `
        (Get-SchemaProperty `
            $FixtureManifestSchema 'reference_snapshot_revision' 'fixture-manifest schema') `
        'referenceRevision' 'fixture-manifest reference_snapshot_revision'
    $manifestSha256Schema = Get-SchemaDefinition `
        $FixtureManifestSchema 'sha256' 'fixture-manifest schema'
    if ((Assert-StringProperty $manifestSha256Schema 'type' 'fixture-manifest SHA-256') -cne 'string' -or
        (Assert-StringProperty $manifestSha256Schema 'pattern' 'fixture-manifest SHA-256') -cne $sha256Pattern) {
        throw 'fixture-manifest SHA-256 definition must require 64 lowercase hexadecimal digits.'
    }
    Assert-SchemaDefinitionReference `
        (Get-SchemaProperty $FixtureManifestSchema 'generator' 'fixture-manifest schema') `
        'generatorIdentity' 'fixture-manifest generator schema'
    $manifestGeneratorSchema = Get-SchemaDefinition `
        $FixtureManifestSchema 'generatorIdentity' 'fixture-manifest schema'
    Assert-SchemaRequiredPropertySet $manifestGeneratorSchema @(
        'id', 'version', 'implementation', 'implementation_spdx_license', 'command', 'options'
    ) 'fixture-manifest generator schema'
    Assert-SchemaConstant `
        (Get-SchemaProperty `
            $manifestGeneratorSchema 'implementation_spdx_license' `
            'fixture-manifest generator schema') `
        'Apache-2.0' 'fixture-manifest generator implementation license'
    Assert-SchemaDefinitionReference `
        (Get-SchemaArrayItem $FixtureManifestSchema 'fixtures' 'fixture-manifest schema') `
        'fixture' 'fixture-manifest fixtures item schema'
    $fixtureSchema = Get-SchemaDefinition $FixtureManifestSchema 'fixture' 'fixture-manifest schema'
    Assert-SchemaRequiredPropertySet $fixtureSchema @(
        'id', 'kind', 'coverage', 'creator', 'source', 'spdx_license', 'generation',
        'reference_snapshot_revision', 'oracle_sha256', 'input_sha256', 'output',
        'redistribution_class'
    ) 'fixture-manifest fixture schema'
    Assert-SchemaConstant `
        (Get-SchemaProperty $fixtureSchema 'spdx_license' 'fixture-manifest fixture schema') `
        'CC0-1.0' 'fixture-manifest fixture SPDX license'
    Assert-SchemaConstant `
        (Get-SchemaProperty $fixtureSchema 'redistribution_class' 'fixture-manifest fixture schema') `
        'project-authored-redistributable' 'fixture-manifest fixture redistribution class'
    Assert-SchemaDefinitionReference `
        (Get-SchemaProperty `
            $fixtureSchema 'reference_snapshot_revision' 'fixture-manifest fixture schema') `
        'referenceRevision' 'fixture-manifest fixture reference_snapshot_revision'
    Assert-NullableOracleSchema `
        (Get-SchemaProperty $fixtureSchema 'oracle_sha256' 'fixture-manifest fixture schema') `
        'fixture-manifest fixture oracle_sha256'
    Assert-SchemaDefinitionReference `
        (Get-SchemaProperty $fixtureSchema 'input_sha256' 'fixture-manifest fixture schema') `
        'sha256' 'fixture-manifest fixture input_sha256'
    Assert-SchemaDefinitionReference `
        (Get-SchemaProperty $fixtureSchema 'generation' 'fixture-manifest fixture schema') `
        'generation' 'fixture-manifest fixture generation schema'
    Assert-SchemaRequiredPropertySet `
        (Get-SchemaDefinition $FixtureManifestSchema 'generation' 'fixture-manifest schema') @(
        'procedure', 'command', 'options'
    ) 'fixture-manifest generation schema'
    Assert-SchemaDefinitionReference `
        (Get-SchemaProperty $fixtureSchema 'output' 'fixture-manifest fixture schema') `
        'output' 'fixture-manifest fixture output schema'
    Assert-SchemaRequiredPropertySet `
        (Get-SchemaDefinition $FixtureManifestSchema 'output' 'fixture-manifest schema') @(
        'path', 'sha256'
    ) 'fixture-manifest output schema'
    Assert-SchemaDefinitionReference `
        (Get-SchemaProperty `
            (Get-SchemaDefinition $FixtureManifestSchema 'output' 'fixture-manifest schema') `
            'sha256' 'fixture-manifest output schema') `
        'sha256' 'fixture-manifest output sha256'
    Assert-SchemaDefinitionReference `
        (Get-SchemaArrayItem $FixtureManifestSchema 'goldens' 'fixture-manifest schema') `
        'golden' 'fixture-manifest goldens item schema'
    $goldenSchema = Get-SchemaDefinition $FixtureManifestSchema 'golden' 'fixture-manifest schema'
    Assert-SchemaRequiredPropertySet $goldenSchema @(
        'id', 'sha256', 'path', 'source_fixture_ids'
    ) 'fixture-manifest golden schema'
    Assert-SchemaDefinitionReference `
        (Get-SchemaProperty $goldenSchema 'sha256' 'fixture-manifest golden schema') `
        'sha256' 'fixture-manifest golden sha256'

    Assert-SchemaRequiredPropertySet $GeneratorSchema @(
        'schema_version', 'generator_id', 'generator_version', 'algorithm', 'recipes'
    ) 'generator schema'
    Assert-SchemaConstant `
        (Get-SchemaProperty $GeneratorSchema 'schema_version' 'generator schema') `
        1 'generator schema_version'
    $recipeSchema = Get-SchemaArrayItem $GeneratorSchema 'recipes' 'generator schema'
    Assert-SchemaRequiredPropertySet $recipeSchema @(
        'id', 'kind', 'coverage', 'input', 'output_path', 'parameters'
    ) 'generator recipe schema'

    Assert-SchemaRequiredPropertySet $RebaselineSchema @(
        'schema_version', 'golden_id', 'status', 'old_sha256', 'new_sha256',
        'source_fixture_sha256s', 'oracle_sha256', 'generator', 'configuration',
        'affected_case_ids', 'rationale', 'semantic_difference', 'approval'
    ) 'rebaseline-record schema'
    Assert-SchemaConstant `
        (Get-SchemaProperty $RebaselineSchema 'schema_version' 'rebaseline-record schema') `
        1 'rebaseline-record schema_version'
    Assert-SchemaConstant `
        (Get-SchemaProperty $RebaselineSchema 'status' 'rebaseline-record schema') `
        'approved' 'rebaseline-record status'
    $rebaselineSha256Schema = Get-SchemaDefinition `
        $RebaselineSchema 'sha256' 'rebaseline-record schema'
    if ((Assert-StringProperty $rebaselineSha256Schema 'type' 'rebaseline-record SHA-256') -cne 'string' -or
        (Assert-StringProperty $rebaselineSha256Schema 'pattern' 'rebaseline-record SHA-256') -cne $sha256Pattern) {
        throw 'rebaseline-record SHA-256 definition must require 64 lowercase hexadecimal digits.'
    }
    foreach ($digestProperty in @('old_sha256', 'new_sha256')) {
        Assert-SchemaDefinitionReference `
            (Get-SchemaProperty $RebaselineSchema $digestProperty 'rebaseline-record schema') `
            'sha256' "rebaseline-record $digestProperty"
    }
    $sourceHashItems = Get-SchemaArrayItem `
        $RebaselineSchema 'source_fixture_sha256s' 'rebaseline-record schema'
    Assert-SchemaDefinitionReference `
        $sourceHashItems 'sha256' 'rebaseline-record source_fixture_sha256s item'
    Assert-NullableOracleSchema `
        (Get-SchemaProperty $RebaselineSchema 'oracle_sha256' 'rebaseline-record schema') `
        'rebaseline-record oracle_sha256'
    Assert-SchemaRequiredPropertySet `
        (Get-SchemaProperty $RebaselineSchema 'generator' 'rebaseline-record schema') @(
        'id', 'version'
    ) 'rebaseline-record generator schema'
    Assert-SchemaRequiredPropertySet `
        (Get-SchemaProperty $RebaselineSchema 'approval' 'rebaseline-record schema') @(
        'approver', 'approved_at', 'decision'
    ) 'rebaseline-record approval schema'
    $approvalSchema = Get-SchemaProperty $RebaselineSchema 'approval' 'rebaseline-record schema'
    Assert-SchemaConstant `
        (Get-SchemaProperty $approvalSchema 'decision' 'rebaseline-record approval schema') `
        'approved' 'rebaseline-record approval decision'
    $approvalDateSchema = Get-SchemaProperty `
        $approvalSchema 'approved_at' 'rebaseline-record approval schema'
    if ((Assert-StringProperty `
            $approvalDateSchema 'format' 'rebaseline-record approval approved_at') -cne 'date-time') {
        throw 'rebaseline-record approval approved_at must declare date-time format.'
    }
    $affectedCaseSchema = Get-SchemaArrayItem `
        $RebaselineSchema 'affected_case_ids' 'rebaseline-record schema'
    $caseIdPattern = '^CV1-[a-z0-9]+(?:-[a-z0-9]+)*(?:\.[a-z0-9]+(?:-[a-z0-9]+)*){4}$'
    if ((Assert-StringProperty `
            $affectedCaseSchema 'pattern' 'rebaseline-record affected case id') -cne $caseIdPattern) {
        throw 'rebaseline-record affected_case_ids must require exact CV1 case identities.'
    }
}

<#
.SYNOPSIS
Rejects unaccounted regular files or reparse points anywhere in the synthetic corpus.

.PARAMETER Root
Canonical corpus root.

.PARAMETER AccountedPaths
Case-insensitive set of paths accounted for by fixture and golden records or exact supporting files.

.OUTPUTS
None.

.NOTES
The complete tree is a closed inventory so the CC0 directory policy cannot cover unknown payloads.
#>
function Test-AccountedCorpus {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,
        [Parameter(Mandatory = $true)]
        [System.Collections.Generic.HashSet[string]] $AccountedPaths
    )

    foreach ($item in @(Get-ChildItem -LiteralPath $Root -Recurse -Force)) {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Fixture corpus must not contain reparse points: $($item.FullName)"
        }
        if (-not $item.PSIsContainer) {
            $relativePath = [System.IO.Path]::GetRelativePath($Root, $item.FullName).Replace('\', '/')
            if (-not $AccountedPaths.Contains($relativePath)) {
                throw "Unaccounted fixture object: $relativePath"
            }
        }
    }
}

<#
.SYNOPSIS
Verifies the committed local-fixture boundary contains no tracked data objects.

.PARAMETER ReactorRoot
Canonical repository root used for Git enumeration.

.OUTPUTS
None.

.NOTES
The placeholder and discovery README are the only permitted tracked local-corpus paths; the audit
does not enumerate or modify ignored local evidence.
#>
function Test-CommittedLocalBoundary {
    param(
        [Parameter(Mandatory = $true)]
        [string] $ReactorRoot
    )

    $trackedLocalPaths = @(& git -C $ReactorRoot ls-files -- 'tests/fixtures/local')
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to enumerate tracked local-fixture paths with Git.'
    }
    $allowedTrackedPaths = @(
        'tests/fixtures/local/.gitkeep',
        'tests/fixtures/local/README.md'
    )
    foreach ($path in $trackedLocalPaths) {
        $normalizedPath = ([string] $path).Replace('\', '/')
        if ($normalizedPath -cnotin $allowedTrackedPaths) {
            throw "Local or proprietary fixture material is tracked: $normalizedPath"
        }
    }
    foreach ($discoveryPath in @(
            'tests/fixtures/local/.gitkeep',
            'tests/fixtures/local/README.md'
        )) {
        if (-not (Test-Path -LiteralPath (Join-Path $ReactorRoot $discoveryPath) -PathType Leaf)) {
            throw "Missing local-corpus discovery file: $discoveryPath"
        }
    }
    & git -C $ReactorRoot check-ignore --no-index --quiet -- `
        'tests/fixtures/local/corpus/__fixture-audit-probe__.ba2'
    if ($LASTEXITCODE -ne 0) {
        throw 'The local fixture corpus is not protected by a Git ignore rule.'
    }
}

$corpusFullPath = [System.IO.Path]::GetFullPath($CorpusRoot)
if (-not (Test-Path -LiteralPath $corpusFullPath -PathType Container)) {
    throw "Fixture corpus root does not exist: $corpusFullPath"
}
$corpusRootItem = Get-Item -Force -LiteralPath $corpusFullPath
if (($corpusRootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw "Fixture corpus root must be a regular directory, not a reparse point: $corpusFullPath"
}

$manifest = Read-FixtureJson (Join-Path $corpusFullPath 'manifest.json') 'fixture manifest'
$generatorDocument = Read-FixtureJson (Join-Path $corpusFullPath 'generator.json') 'generator manifest'
$fixtureManifestSchema = Read-FixtureJson `
    (Join-Path $corpusFullPath 'fixture-manifest.schema.json') 'fixture-manifest schema'
$generatorSchema = Read-FixtureJson `
    (Join-Path $corpusFullPath 'generator.schema.json') 'generator schema'
$rebaselineSchema = Read-FixtureJson `
    (Join-Path $corpusFullPath 'rebaseline-record.schema.json') 'rebaseline-record schema'

Test-FixtureSchemaContract $fixtureManifestSchema $generatorSchema $rebaselineSchema

$manifestSchemaVersion = (Get-RequiredJsonProperty $manifest 'schema_version' 'Fixture manifest').Value
if ($manifestSchemaVersion -isnot [long] -or $manifestSchemaVersion -ne 1) {
    throw 'Fixture manifest schema_version must be 1.'
}
[void](Assert-StringProperty $manifest 'corpus_id' 'Fixture manifest')
$generatedOn = Assert-StringProperty $manifest 'generated_on' 'Fixture manifest'
if ($generatedOn -cnotmatch '^\d{4}-\d{2}-\d{2}$') {
    throw 'Fixture manifest generated_on must use the ISO yyyy-MM-dd form.'
}
try {
    [void][datetime]::ParseExact(
        $generatedOn,
        'yyyy-MM-dd',
        [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::None
    )
}
catch {
    throw "Fixture manifest generated_on is not a calendar date: $generatedOn"
}
$manifestRevision = Assert-StringProperty $manifest 'reference_snapshot_revision' 'Fixture manifest'
if ($manifestRevision -cne $referenceSnapshotRevision) {
    throw "Fixture manifest must bind Reference Snapshot $referenceSnapshotRevision."
}

$manifestGenerator = (Get-RequiredJsonProperty $manifest 'generator' 'Fixture manifest').Value
foreach ($property in @('id', 'version', 'implementation', 'command')) {
    [void](Assert-StringProperty $manifestGenerator $property 'Fixture manifest generator')
}
$implementationLicense = Assert-StringProperty `
    $manifestGenerator 'implementation_spdx_license' 'Fixture manifest generator'
if ($implementationLicense -cne 'Apache-2.0') {
    throw 'Fixture generator implementation must remain Apache-2.0.'
}
[void](Assert-ConfigurationProperty $manifestGenerator 'options' 'Fixture manifest generator')

$fixtureProperty = (Get-RequiredJsonProperty $manifest 'fixtures' 'Fixture manifest').Value
if ($fixtureProperty -isnot [System.Array] -or @($fixtureProperty).Count -eq 0) {
    throw 'Fixture manifest must declare a non-empty fixtures array.'
}
$goldenProperty = (Get-RequiredJsonProperty $manifest 'goldens' 'Fixture manifest').Value
if ($goldenProperty -isnot [System.Array] -or @($goldenProperty).Count -eq 0) {
    throw 'Fixture manifest must declare a non-empty goldens array.'
}

$fixtureIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$fixtureById = [System.Collections.Generic.Dictionary[string, object]]::new(
    [System.StringComparer]::Ordinal
)
$fixtureOutputPaths = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
$accountedPaths = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
$coverage = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)

foreach ($fixture in @($fixtureProperty)) {
    $fixtureId = Assert-StringProperty $fixture 'id' 'Fixture record'
    if ($fixtureId -cnotmatch '^[a-z0-9]+(?:-[a-z0-9]+)*$') {
        throw "Fixture id is not a lowercase kebab-case identity: $fixtureId"
    }
    if (-not $fixtureIds.Add($fixtureId)) {
        throw "Duplicate fixture id: $fixtureId"
    }
    $context = "Fixture $fixtureId"
    $kind = Assert-StringProperty $fixture 'kind' $context
    if ($kind -cnotin @(
            'structural-template', 'generated-archive', 'malformed-archive', 'scenario',
            'golden-observation'
        )) {
        throw "$context declares an unsupported kind: $kind"
    }
    foreach ($property in @('creator', 'source')) {
        [void](Assert-StringProperty $fixture $property $context)
    }
    $fixtureCoverage = @(Assert-StringArrayProperty $fixture 'coverage' $context)
    foreach ($coverageId in $fixtureCoverage) {
        if ($coverageId -cnotmatch '^[a-z0-9]+(?:-[a-z0-9]+)*$') {
            throw "$context has an invalid coverage identifier: $coverageId"
        }
        [void]$coverage.Add($coverageId)
    }
    $spdxLicense = Assert-StringProperty $fixture 'spdx_license' $context
    if ($spdxLicense -cne 'CC0-1.0') {
        throw "$context must use the project-authored fixture license CC0-1.0."
    }
    $redistributionClass = Assert-StringProperty $fixture 'redistribution_class' $context
    if ($redistributionClass -cne 'project-authored-redistributable') {
        throw "$context must be classified as project-authored-redistributable."
    }
    $generation = (Get-RequiredJsonProperty $fixture 'generation' $context).Value
    [void](Assert-StringProperty $generation 'procedure' "$context generation")
    $generationCommand = Assert-StringProperty $generation 'command' "$context generation"
    if ($generationCommand -cne $manifestGenerator.command) {
        throw "$context generation command disagrees with the corpus generator command."
    }
    $generationOptions = Assert-ConfigurationProperty $generation 'options' "$context generation"
    $fixtureRevision = Assert-StringProperty $fixture 'reference_snapshot_revision' $context
    if ($fixtureRevision -cne $referenceSnapshotRevision) {
        throw "$context must bind Reference Snapshot $referenceSnapshotRevision."
    }
    $oracleProperty = Get-RequiredJsonProperty $fixture 'oracle_sha256' $context
    if ($null -ne $oracleProperty.Value) {
        if ($oracleProperty.Value -isnot [string] -or $oracleProperty.Value -cne $oracleSha256) {
            throw "$context oracle_sha256 must be null or exact Conformance Oracle digest $oracleSha256."
        }
    }
    $inputSha256 = Assert-Sha256Property $fixture 'input_sha256' $context
    $output = (Get-RequiredJsonProperty $fixture 'output' $context).Value
    $outputPath = Assert-StringProperty $output 'path' "$context output"
    if (-not $outputPath.StartsWith('artifacts/', [System.StringComparison]::Ordinal) -and
        -not $outputPath.StartsWith('goldens/sha256/', [System.StringComparison]::Ordinal)) {
        throw "$context output must be stored below artifacts/ or goldens/sha256/: $outputPath"
    }
    $outputSha256 = Assert-Sha256Property $output 'sha256' "$context output"
    if (-not $fixtureOutputPaths.Add($outputPath)) {
        throw "Duplicate fixture output path: $outputPath"
    }
    [void](Assert-CorpusObject $corpusFullPath $outputPath $outputSha256 $context)
    if ($outputPath.StartsWith('artifacts/', [System.StringComparison]::Ordinal)) {
        [void]$accountedPaths.Add($outputPath)
    }
    $fixtureById[$fixtureId] = [pscustomobject]@{
        outputPath = $outputPath
        outputSha256 = $outputSha256
        inputSha256 = $inputSha256
        kind = $kind
        coverage = $fixtureCoverage
        generationOptions = $generationOptions
    }
}

$goldenIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($golden in @($goldenProperty)) {
    $goldenId = Assert-StringProperty $golden 'id' 'Golden record'
    if (-not $goldenIds.Add($goldenId)) {
        throw "Duplicate golden id: $goldenId"
    }
    $context = "Golden $goldenId"
    $goldenSha256 = Assert-Sha256Property $golden 'sha256' $context
    $goldenPath = Assert-StringProperty $golden 'path' $context
    if (-not $goldenPath.StartsWith('goldens/sha256/', [System.StringComparison]::Ordinal)) {
        throw "$context must be stored below goldens/sha256/: $goldenPath"
    }
    $goldenFileName = [System.IO.Path]::GetFileName($goldenPath)
    if ($goldenFileName -cnotmatch "^$goldenSha256(?:\.[a-z0-9][a-z0-9._-]*)?$") {
        throw "$context filename must be content-addressed by $goldenSha256`: $goldenPath"
    }
    foreach ($sourceFixtureId in @(Assert-StringArrayProperty $golden 'source_fixture_ids' $context)) {
        if (-not $fixtureIds.Contains($sourceFixtureId)) {
            throw "$context references unknown source fixture id: $sourceFixtureId"
        }
    }
    if (-not $fixtureById.ContainsKey($goldenId) -or
        $fixtureById[$goldenId].outputPath -cne $goldenPath -or
        $fixtureById[$goldenId].outputSha256 -cne $goldenSha256) {
        throw "$context must have a matching fixture output record."
    }
    [void](Assert-CorpusObject $corpusFullPath $goldenPath $goldenSha256 $context)
    if (-not $accountedPaths.Add($goldenPath)) {
        throw "Duplicate golden object path: $goldenPath"
    }
}

$generatorSchemaVersion = (Get-RequiredJsonProperty `
        $generatorDocument 'schema_version' 'Generator manifest').Value
if ($generatorSchemaVersion -isnot [long] -or $generatorSchemaVersion -ne 1) {
    throw 'Generator manifest schema_version must be 1.'
}
$generatorId = Assert-StringProperty $generatorDocument 'generator_id' 'Generator manifest'
$generatorVersion = Assert-StringProperty $generatorDocument 'generator_version' 'Generator manifest'
[void](Assert-StringProperty $generatorDocument 'algorithm' 'Generator manifest')
if ($generatorId -cne $manifestGenerator.id -or $generatorVersion -cne $manifestGenerator.version) {
    throw 'Generator manifest identity must match the fixture manifest generator identity.'
}
$recipeProperty = (Get-RequiredJsonProperty $generatorDocument 'recipes' 'Generator manifest').Value
if ($recipeProperty -isnot [System.Array] -or @($recipeProperty).Count -eq 0) {
    throw 'Generator manifest must declare a non-empty recipes array.'
}
$recipeIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
foreach ($recipe in @($recipeProperty)) {
    $recipeId = Assert-StringProperty $recipe 'id' 'Generator recipe'
    if (-not $recipeIds.Add($recipeId)) {
        throw "Duplicate generator recipe id: $recipeId"
    }
    $context = "Generator recipe $recipeId"
    if (-not $fixtureById.ContainsKey($recipeId)) {
        throw "$context has no one-to-one fixture record."
    }
    $recipeKind = Assert-StringProperty $recipe 'kind' $context
    if ($recipeKind -cne $fixtureById[$recipeId].kind) {
        throw "$context kind disagrees with its fixture record: $recipeKind"
    }
    $recipeCoverage = @(Assert-StringArrayProperty $recipe 'coverage' $context)
    $fixtureCoverage = @($fixtureById[$recipeId].coverage)
    if ($recipeCoverage.Count -ne $fixtureCoverage.Count) {
        throw "$context coverage disagrees with its fixture record."
    }
    for ($index = 0; $index -lt $recipeCoverage.Count; $index++) {
        if ($recipeCoverage[$index] -cne $fixtureCoverage[$index]) {
            throw "$context coverage disagrees with its fixture record."
        }
    }
    $recipeInput = (Get-RequiredJsonProperty $recipe 'input' $context).Value
    if ($recipeInput -isnot [string] -or [string]::IsNullOrWhiteSpace($recipeInput)) {
        throw "$context must declare its exact non-empty UTF-8 string input."
    }
    $recipeOutputPath = Assert-StringProperty $recipe 'output_path' $context
    if ($recipeOutputPath -cne $fixtureById[$recipeId].outputPath) {
        throw "$context output_path disagrees with its fixture record: $recipeOutputPath"
    }
    $recipeInputBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($recipeInput)
    $recipeInputSha256 = [System.Convert]::ToHexString(
        [System.Security.Cryptography.SHA256]::HashData($recipeInputBytes)
    ).ToLowerInvariant()
    if ($recipeInputSha256 -cne $fixtureById[$recipeId].inputSha256) {
        throw "$context input SHA-256 disagrees with its fixture record: $recipeInputSha256"
    }
    $recipeParameters = Assert-ConfigurationProperty $recipe 'parameters' $context
    if (-not (Test-ConfigurationMap $recipeParameters $fixtureById[$recipeId].generationOptions)) {
        throw "$context parameters disagree with its fixture generation options."
    }
}

foreach ($fixtureId in $fixtureIds) {
    if (-not $recipeIds.Contains($fixtureId)) {
        throw "Fixture $fixtureId has no one-to-one generator recipe."
    }
}

foreach ($coverageId in $requiredCoverage) {
    if (-not $coverage.Contains($coverageId)) {
        throw "Fixture corpus is missing required coverage category: $coverageId"
    }
}
foreach ($fixtureId in $requiredIndependentFixtureIds) {
    if (-not $fixtureIds.Contains($fixtureId)) {
        throw "Fixture corpus is missing independently authored fixture: $fixtureId"
    }
    if (-not $recipeIds.Contains($fixtureId)) {
        throw "Generator manifest is missing independently authored recipe: $fixtureId"
    }
}

# Only these exact support paths are exempt from per-object provenance; filenames in other
# directories must still be accounted for, including nested README or schema lookalikes.
foreach ($supportPath in @(
    'README.md',
    'fixture-manifest.schema.json',
    'generator.schema.json',
    'rebaseline-record.schema.json',
    'manifest.json',
    'generator.json',
    'goldens/README.md',
    'goldens/index.json'
)) {
    [void] $accountedPaths.Add($supportPath)
}
Test-AccountedCorpus $corpusFullPath $accountedPaths

$reactorRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$committedCorpusRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $reactorRoot 'tests/fixtures/synthetic')
)
if ($corpusFullPath.TrimEnd('\', '/').Equals(
        $committedCorpusRoot.TrimEnd('\', '/'),
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
    Test-CommittedLocalBoundary $reactorRoot
}

Write-Output (
    'Fixture corpus verification passed: {0} fixtures, {1} goldens, and {2} generator recipes verified read-only.' -f
    @($fixtureProperty).Count, @($goldenProperty).Count, @($recipeProperty).Count
)
