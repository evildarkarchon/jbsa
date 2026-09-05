<#
.SYNOPSIS
Validates JBSA licensing/provenance metadata and audits optional release-input bytes.

.PARAMETER ReleaseInputRoot
An optional directory whose complete file inventory must contain no proprietary fixtures,
unapproved native payloads, or unaccounted release artifacts.

.PARAMETER ReleaseInputManifest
An optional JSON manifest that accounts for every regular file below ReleaseInputRoot by relative
path, SHA-256 digest, kind, and source. It is required for a non-empty release-input directory after
prohibited fixture and native checks have run.

.PARAMETER RequireGeneratedArtifacts
Requires and validates the aggregate CycloneDX SBOM. Maven uses this after SBOM generation; callers
that only need repository or candidate-input validation can omit it.

.PARAMETER GeneratedSbomPath
Optional exact CycloneDX JSON path used with RequireGeneratedArtifacts. Tests may supply an owned
fixture; normal builds use target/compliance/jbsa.cdx.json.

.PARAMETER ReactorVersion
Optional effective Maven reactor version. Maven supplies this explicitly so command-line revision
overrides resolve the matching build outputs; standalone callers use the root POM revision.

.NOTES
This engineering gate does not make a legal determination. Unclear rights or provenance remain a
stop condition even when the mechanical checks pass.
#>
[CmdletBinding()]
param(
    [string] $ReleaseInputRoot,
    [string] $ReleaseInputManifest,
    [switch] $RequireGeneratedArtifacts,
    [string] $GeneratedSbomPath,
    [string] $ReactorVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$reactorRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$dependencyInventoryPath = Join-Path $reactorRoot 'compliance/dependency-inventory.json'
$nativeInventoryPath = Join-Path $reactorRoot 'compliance/native-payload-inventory.json'
$complianceOutput = Join-Path $reactorRoot 'target/compliance'
$generatedNoticesPath = Join-Path $complianceOutput 'THIRD-PARTY-NOTICES.md'
$committedNoticesPath = Join-Path $reactorRoot 'THIRD-PARTY-NOTICES.md'
$generatedReleaseNotesPath = Join-Path $complianceOutput 'RELEASE-NOTES.md'
$committedReleaseNotesPath = Join-Path $reactorRoot 'RELEASE-NOTES.md'
$sha256Pattern = '^[0-9a-f]{64}$'
$proprietaryArchiveExtensions = @('.bsa', '.ba2', '.esm', '.esp', '.esl', '.dds')
$nativeLibraryExtensions = @('.dll', '.so', '.dylib')
$nestedArchiveExtensions = @('.jar', '.zip')
# This cap bounds inflation before hashing while covering ordinary dependency and application JARs.
$maximumArchiveEntryBytes = 268435456
# EOCD plus its maximum ZIP comment is sufficient to detect archives with an executable preamble.
$maximumZipTrailerBytes = 65557
# Four nested layers cover expected packaging while bounding adversarial archive recursion.
$maximumArchiveDepth = 4

<#
.SYNOPSIS
Reads a versioned JSON inventory and rejects an absent or unsupported schema.

.PARAMETER Path
Exact inventory file to read.

.OUTPUTS
The parsed schema-version-1 inventory object.

.NOTES
Throws a terminating error when the file is absent, malformed, or uses an unsupported schema.
#>
function Read-ComplianceInventory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing compliance inventory: $Path"
    }
    $inventory = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json -Depth 100
    if ($inventory.schemaVersion -ne 1) {
        throw "Unsupported compliance inventory schema in ${Path}: $($inventory.schemaVersion)"
    }
    if ($null -eq $inventory.entries) {
        throw "Compliance inventory has no entries array: $Path"
    }
    return $inventory
}

<#
.SYNOPSIS
Returns the default reactor version declared by the root Maven POM.

.PARAMETER Path
Exact root POM path to parse.

.OUTPUTS
The non-empty revision property value.

.NOTES
Throws a terminating error when the POM is malformed or omits its revision property.
#>
function Get-DeclaredReactorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    $rootPom = [xml](Get-Content -Raw -LiteralPath $Path)
    $namespaces = [System.Xml.XmlNamespaceManager]::new($rootPom.NameTable)
    $namespaces.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
    $revisionNode = $rootPom.SelectSingleNode('/m:project/m:properties/m:revision', $namespaces)
    if ($null -eq $revisionNode -or [string]::IsNullOrWhiteSpace($revisionNode.InnerText)) {
        throw 'The root POM does not declare the reactor revision.'
    }
    return $revisionNode.InnerText.Trim()
}

<#
.SYNOPSIS
Requires a non-empty string property so unresolved provenance cannot look like approval.

.PARAMETER Object
Inventory object that must own the property.

.PARAMETER Property
Required property name.

.PARAMETER Context
Stable identity included in a validation failure.

.OUTPUTS
None.

.NOTES
Throws a terminating error when the property is absent, non-string, or empty.
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

    $propertyValue = $Object.PSObject.Properties[$Property]
    if ($null -eq $propertyValue -or $propertyValue.Value -isnot [string] -or
        [string]::IsNullOrWhiteSpace($propertyValue.Value)) {
        throw "$Context must declare a non-empty $Property."
    }
}

<#
.SYNOPSIS
Requires a string-list property with at least one evidence item.

.PARAMETER Object
Inventory object that must own the list.

.PARAMETER Property
Required property name.

.PARAMETER Context
Stable identity included in a validation failure.

.OUTPUTS
None.

.NOTES
Throws a terminating error when the property is absent, empty, or contains a non-string value.
#>
function Assert-StringListProperty {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Object,
        [Parameter(Mandatory = $true)]
        [string] $Property,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    $propertyValue = $Object.PSObject.Properties[$Property]
    $values = @()
    if ($null -ne $propertyValue) {
        $values = @($propertyValue.Value)
    }
    $invalidValues = @($values | Where-Object {
            $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_)
        })
    if ($values.Count -eq 0 -or $invalidValues.Count -ne 0) {
        throw "$Context must declare one or more non-empty $Property values."
    }
}

<#
.SYNOPSIS
Returns the stable Maven identity used for duplicate and cross-inventory checks.

.PARAMETER Entry
Dependency-like object with group, artifact, packaging, classifier, and version fields.

.OUTPUTS
One Maven-coordinate identity string.

.NOTES
Property-access errors from a malformed caller-supplied entry are intentionally propagated.
#>
function Get-DependencyKey {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Entry
    )

    $classifier = if ($null -eq $Entry.classifier) { '' } else { [string] $Entry.classifier }
    return '{0}:{1}:{2}:{3}:{4}' -f
        $Entry.groupId, $Entry.artifactId, $Entry.packaging, $classifier, $Entry.version
}

<#
.SYNOPSIS
Verifies the evidence fields shared by dependency and native redistribution records.

.PARAMETER Entry
Dependency or native payload entry to validate.

.PARAMETER Context
Stable identity included in validation failures.

.OUTPUTS
None.

.NOTES
Throws a terminating error for incomplete, malformed, or internally inconsistent provenance.
#>
function Assert-ProvenanceRecord {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Entry,
        [Parameter(Mandatory = $true)]
        [string] $Context
    )

    if ($Entry.sha256 -cnotmatch $sha256Pattern) {
        throw "$Context has an invalid lowercase SHA-256 digest."
    }
    Assert-StringProperty $Entry.license 'spdx' "$Context license"
    Assert-StringProperty $Entry.license 'evidence' "$Context license"
    Assert-StringListProperty $Entry 'requiredNotices' $Context
    Assert-StringProperty $Entry.source 'repository' "$Context source"
    Assert-StringProperty $Entry.source 'revision' "$Context source"
    Assert-StringProperty $Entry.source 'buildProvenance' "$Context source"
    if ($Entry.source.revision -cnotmatch '^[0-9a-f]{40}$') {
        throw "$Context source revision must be a lowercase 40-character Git commit."
    }
    if ($null -eq $Entry.redistribution -or
        $Entry.redistribution.PSObject.Properties['approved'].Value -isnot [bool]) {
        throw "$Context must declare a boolean redistribution.approved value."
    }
    Assert-StringProperty $Entry.redistribution 'evidence' "$Context redistribution"
    if ($Entry.redistribution.releaseArtifacts -isnot [array]) {
        throw "$Context must declare redistribution.releaseArtifacts as an array."
    }

    $releaseArtifacts = @($Entry.redistribution.releaseArtifacts)
    $artifactNames = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($artifact in $releaseArtifacts) {
        # Thin Maven artifacts do not embed dependency bytes; only the application ZIP may contain them.
        if ($artifact -isnot [string] -or
            $artifact -cnotmatch '^jbsa-cli-[0-9A-Za-z.+-]+-windows-x64\.zip$' -or
            -not $artifactNames.Add($artifact)) {
            throw "$Context has an invalid or duplicate releaseArtifacts containing artifact."
        }
    }
    if ($Entry.redistribution.approved -and $releaseArtifacts.Count -eq 0) {
        throw "$Context is redistribution-approved but names no containing release artifact."
    }
    if (-not $Entry.redistribution.approved -and $releaseArtifacts.Count -ne 0) {
        throw "$Context is not redistribution-approved and must not name release artifacts."
    }
}

<#
.SYNOPSIS
Validates dependency identities and builds a lookup keyed by full Maven coordinates.

.PARAMETER Inventory
Parsed dependency inventory whose entries must be complete and unique.

.OUTPUTS
A hashtable from Maven-coordinate identity to validated dependency entry.

.NOTES
Throws a terminating error for malformed evidence or duplicate dependency identities.
#>
function Get-ValidatedDependencyLookup {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Inventory
    )

    $lookup = @{}
    foreach ($entry in @($Inventory.entries)) {
        foreach ($property in @('groupId', 'artifactId', 'version', 'packaging', 'use')) {
            Assert-StringProperty $entry $property 'Dependency inventory entry'
        }
        if ($entry.PSObject.Properties['classifier'].Value -ne $null -and
            $entry.classifier -isnot [string]) {
            throw 'Dependency classifier must be a string or null.'
        }
        if ($entry.PSObject.Properties['containsNativeBytes'].Value -isnot [bool]) {
            throw 'Dependency inventory entry must declare containsNativeBytes as a boolean.'
        }
        Assert-ProvenanceRecord $entry (Get-DependencyKey $entry)
        if ($entry.redistribution.approved) {
            if ($entry.PSObject.Properties['blockedBy'].Value -isnot [array] -or
                @($entry.blockedBy).Count -ne 0) {
                throw "$(Get-DependencyKey $entry) is approved and must have an empty blockedBy array."
            }
        } else {
            Assert-StringListProperty $entry 'blockedBy' (Get-DependencyKey $entry)
        }

        $key = Get-DependencyKey $entry
        if ($lookup.ContainsKey($key)) {
            throw "Duplicate dependency inventory identity: $key"
        }
        $lookup[$key] = $entry
    }
    return $lookup
}

<#
.SYNOPSIS
Validates native payload evidence and ties every payload to its checksummed Maven container.

.PARAMETER Inventory
Parsed native payload inventory whose entries must be complete and unique.

.PARAMETER DependencyLookup
Validated dependency entries keyed by Maven-coordinate identity.

.OUTPUTS
A hashtable from native payload SHA-256 to validated native entry.

.NOTES
Throws a terminating error for malformed evidence, missing containers, or checksum disagreement.
#>
function Get-ValidatedNativeHashLookup {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Inventory,
        [Parameter(Mandatory = $true)]
        [hashtable] $DependencyLookup
    )

    $hashLookup = @{}
    foreach ($entry in @($Inventory.entries)) {
        foreach ($property in @('name', 'pathInContainer', 'platform', 'componentVersion')) {
            Assert-StringProperty $entry $property 'Native payload inventory entry'
        }
        Assert-ProvenanceRecord $entry "Native payload $($entry.name)"
        if ($entry.eligibility.PSObject.Properties['pureJavaInsufficient'].Value -isnot [bool]) {
            throw "Native payload $($entry.name) must declare eligibility.pureJavaInsufficient as a boolean."
        }
        Assert-StringProperty $entry.eligibility 'evidence' "Native payload $($entry.name) eligibility"

        foreach ($property in @('groupId', 'artifactId', 'version', 'sha256')) {
            Assert-StringProperty $entry.container $property "Native payload $($entry.name) container"
        }
        if ($entry.container.sha256 -cnotmatch $sha256Pattern) {
            throw "Native payload $($entry.name) container has an invalid lowercase SHA-256 digest."
        }
        $containerIdentity = [pscustomobject]@{
            groupId = $entry.container.groupId
            artifactId = $entry.container.artifactId
            version = $entry.container.version
            packaging = 'jar'
            classifier = $entry.container.classifier
        }
        $containerKey = Get-DependencyKey $containerIdentity
        if (-not $DependencyLookup.ContainsKey($containerKey)) {
            throw "Native payload $($entry.name) references an unrecorded container $containerKey."
        }
        if ($DependencyLookup[$containerKey].sha256 -cne $entry.container.sha256) {
            throw "Native payload $($entry.name) container checksum disagrees with $containerKey."
        }
        if (-not $DependencyLookup[$containerKey].containsNativeBytes) {
            throw "Native payload $($entry.name) container is not marked as containing native bytes."
        }
        if ($entry.redistribution.approved) {
            if (-not $entry.eligibility.pureJavaInsufficient) {
                throw "Native payload $($entry.name) cannot be approved without pure-Java insufficiency evidence."
            }
            $containerDependency = $DependencyLookup[$containerKey]
            if (-not $containerDependency.redistribution.approved) {
                throw "Native payload $($entry.name) cannot be approved while its container is blocked."
            }
            foreach ($releaseArtifact in @($entry.redistribution.releaseArtifacts)) {
                if ($releaseArtifact -cnotmatch '^jbsa-cli-[0-9A-Za-z.+-]+-windows-x64\.zip$') {
                    throw "Native payload $($entry.name) names an unauthorized containing artifact: $releaseArtifact"
                }
                if (@($containerDependency.redistribution.releaseArtifacts) -cnotcontains $releaseArtifact) {
                    throw "Native payload $($entry.name) container does not name $releaseArtifact."
                }
            }
        }
        if ($hashLookup.ContainsKey($entry.sha256)) {
            throw "Duplicate native payload SHA-256 identity: $($entry.sha256)"
        }
        $hashLookup[$entry.sha256] = $entry
    }
    return $hashLookup
}

<#
.SYNOPSIS
Resolves a literal or root-POM property used by an external product dependency.

.PARAMETER Value
Literal version or single Maven property expression to resolve.

.PARAMETER RootPom
Parsed reactor POM that owns pinned version properties.

.PARAMETER Namespaces
Namespace manager configured for RootPom.

.OUTPUTS
The exact resolved dependency version.

.NOTES
Throws a terminating error when a referenced root-POM property is absent or empty.
#>
function Resolve-PomVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Value,
        [Parameter(Mandatory = $true)]
        [xml] $RootPom,
        [Parameter(Mandatory = $true)]
        [System.Xml.XmlNamespaceManager] $Namespaces
    )

    if ($Value -notmatch '^\$\{([^}]+)\}$') {
        return $Value
    }
    $propertyName = $Matches[1]
    $propertyNode = $RootPom.SelectSingleNode("/m:project/m:properties/m:$propertyName", $Namespaces)
    if ($null -eq $propertyNode -or [string]::IsNullOrWhiteSpace($propertyNode.InnerText)) {
        throw "Cannot resolve external dependency version property $Value."
    }
    return $propertyNode.InnerText.Trim()
}

<#
.SYNOPSIS
Requires every external production dependency to be an approved, exact inventory entry.

.PARAMETER DependencyLookup
Validated dependency entries keyed by Maven-coordinate identity.

.OUTPUTS
None.

.NOTES
Throws a terminating error when a product dependency is absent from or blocked by the inventory.
#>
function Test-ProductDependencies {
    param(
        [Parameter(Mandatory = $true)]
        [hashtable] $DependencyLookup
    )

    $rootPom = [xml](Get-Content -Raw -LiteralPath (Join-Path $reactorRoot 'pom.xml'))
    $namespaces = New-Object System.Xml.XmlNamespaceManager($rootPom.NameTable)
    $namespaces.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')

    foreach ($relativePom in @('jbsa/pom.xml', 'jbsa-cli/pom.xml')) {
        $pom = [xml](Get-Content -Raw -LiteralPath (Join-Path $reactorRoot $relativePom))
        $pomNamespaces = New-Object System.Xml.XmlNamespaceManager($pom.NameTable)
        $pomNamespaces.AddNamespace('m', 'http://maven.apache.org/POM/4.0.0')
        foreach ($dependency in @($pom.SelectNodes('/m:project/m:dependencies/m:dependency', $pomNamespaces))) {
            $scopeNode = $dependency.SelectSingleNode('m:scope', $pomNamespaces)
            if ($null -ne $scopeNode -and $scopeNode.InnerText.Trim() -cin @('test', 'provided')) {
                continue
            }
            $versionText = $dependency.version.Trim()
            $version = if ($versionText -cin @('${project.version}', '${revision}')) {
                $ReactorVersion
            } else { Resolve-PomVersion $versionText $rootPom $namespaces }
            $classifierNode = $dependency.SelectSingleNode('m:classifier', $pomNamespaces)
            $classifier = if ($null -eq $classifierNode) { '' } else { $classifierNode.InnerText.Trim() }
            $typeNode = $dependency.SelectSingleNode('m:type', $pomNamespaces)
            $packaging = if ($null -eq $typeNode) { 'jar' } else { $typeNode.InnerText.Trim() }
            # Only the CLI's exact current library output is a production reactor dependency.
            if ($relativePom -ceq 'jbsa-cli/pom.xml' -and
                $dependency.groupId -ceq 'io.github.evildarkarchon' -and
                $dependency.artifactId -ceq 'jbsa' -and $version -ceq $ReactorVersion -and
                $packaging -ceq 'jar' -and $classifier -ceq '') {
                continue
            }
            $key = '{0}:{1}:{2}:{3}:{4}' -f
                $dependency.groupId.Trim(), $dependency.artifactId.Trim(), $packaging, $classifier, $version
            if (-not $DependencyLookup.ContainsKey($key)) {
                throw "External product dependency is absent from the approved inventory: $key"
            }
            if (-not $DependencyLookup[$key].redistribution.approved) {
                throw "External product dependency is not approved for release inputs: $key"
            }
        }
    }
}

<#
.SYNOPSIS
Produces the release notice file solely from currently approved inventory entries.

.PARAMETER DependencyInventory
Parsed dependency inventory.

.PARAMETER NativeInventory
Parsed native payload inventory.

.OUTPUTS
Deterministic LF-terminated Markdown notice text.

.NOTES
Malformed inventory property access is intentionally propagated as a terminating error.
#>
function New-ThirdPartyNoticesText {
    param(
        [Parameter(Mandatory = $true)]
        [object] $DependencyInventory,
        [Parameter(Mandatory = $true)]
        [object] $NativeInventory
    )

    $approvedDependencies = @($DependencyInventory.entries | Where-Object {
            $_.redistribution.approved
        })
    $approvedNative = @($NativeInventory.entries | Where-Object {
            $_.redistribution.approved
        })
    $lines = @(
        '# Third-Party Notices',
        '',
        'This file is generated from `compliance/dependency-inventory.json` and',
        '`compliance/native-payload-inventory.json`.',
        ''
    )

    if ($approvedDependencies.Count -eq 0 -and $approvedNative.Count -eq 0) {
        $lines += @(
            'No third-party dependency or native payload is currently approved for redistribution in a JBSA',
            'release artifact. Selected but unaudited codec candidates remain excluded from release inputs until',
            'their applicable conformance, performance, memory, native-loading, license, notice, and packaging',
            'gates pass.'
        )
        return ($lines -join "`n") + "`n"
    }

    foreach ($entry in $approvedDependencies | Sort-Object groupId, artifactId, classifier) {
        $classifier = if ($null -eq $entry.classifier) { '' } else { ":$($entry.classifier)" }
        $lines += "## $($entry.groupId):$($entry.artifactId):$($entry.version)$classifier"
        $lines += ''
        $lines += "License: $($entry.license.spdx)"
        foreach ($notice in @($entry.requiredNotices)) {
            $lines += "- $notice"
        }
        $lines += ''
    }
    foreach ($entry in $approvedNative | Sort-Object name) {
        $lines += "## $($entry.name)"
        $lines += ''
        $lines += "License: $($entry.license.spdx)"
        foreach ($notice in @($entry.requiredNotices)) {
            $lines += "- $notice"
        }
        $lines += ''
    }
    return ($lines -join "`n") + "`n"
}

<#
.SYNOPSIS
Returns the lowercase SHA-256 digest of one exact file.

.PARAMETER Path
Exact regular file to hash.

.OUTPUTS
A lowercase 64-character hexadecimal digest.

.NOTES
Throws a terminating I/O error when the file cannot be opened or read.
#>
function Get-LowercaseSha256 {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

<#
.SYNOPSIS
Hashes and classifies the remaining payload bytes in one stream.

.PARAMETER Stream
Readable stream positioned at the first payload byte. The function leaves ownership to the caller.

.PARAMETER MaximumBytes
Maximum bytes permitted before inspection aborts. Defaults to the largest stream length.

.OUTPUTS
An object containing the lowercase SHA-256 digest plus native, proprietary-format, and ZIP flags.

.NOTES
Consumes the stream exactly once so non-seekable ZIP entry streams receive the same content-based
checks as regular files. PE/DOS, ELF, and unambiguous Mach-O signatures are treated as native;
BSA, BA2, DDS, and TES3/TES4 plugin signatures identify prohibited game-format material.
#>
function Get-StreamPayloadInspection {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Stream] $Stream,
        [long] $MaximumBytes = [long]::MaxValue
    )

    $header = [byte[]]::new(8)
    $headerLength = 0
    $buffer = [byte[]]::new(81920)
    $zipTrailer = [byte[]]::new($maximumZipTrailerBytes)
    $zipTrailerLength = 0
    $totalBytes = [long] 0
    $sha256 = [System.Security.Cryptography.IncrementalHash]::CreateHash(
        [System.Security.Cryptography.HashAlgorithmName]::SHA256
    )
    try {
        while (($bytesRead = $Stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $totalBytes += $bytesRead
            if ($totalBytes -gt $MaximumBytes) {
                throw "Payload exceeds the inspection size limit of $MaximumBytes bytes."
            }
            if ($headerLength -lt $header.Length) {
                $headerBytes = [Math]::Min($bytesRead, $header.Length - $headerLength)
                [Array]::Copy($buffer, 0, $header, $headerLength, $headerBytes)
                $headerLength += $headerBytes
            }
            if ($bytesRead -ge $zipTrailer.Length) {
                [Array]::Copy(
                    $buffer,
                    $bytesRead - $zipTrailer.Length,
                    $zipTrailer,
                    0,
                    $zipTrailer.Length
                )
                $zipTrailerLength = $zipTrailer.Length
            } else {
                $overflow = [Math]::Max(
                    0,
                    $zipTrailerLength + $bytesRead - $zipTrailer.Length
                )
                if ($overflow -gt 0) {
                    [Array]::Copy(
                        $zipTrailer,
                        $overflow,
                        $zipTrailer,
                        0,
                        $zipTrailerLength - $overflow
                    )
                    $zipTrailerLength -= $overflow
                }
                [Array]::Copy($buffer, 0, $zipTrailer, $zipTrailerLength, $bytesRead)
                $zipTrailerLength += $bytesRead
            }
            $sha256.AppendData($buffer, 0, $bytesRead)
        }
        $hash = [Convert]::ToHexString($sha256.GetHashAndReset()).ToLowerInvariant()
    }
    finally {
        $sha256.Dispose()
    }

    $magic = [Convert]::ToHexString($header)
    $magic32 = $magic.Substring(0, 8)
    $isFatMachO = $false
    if ($headerLength -ge 8 -and $magic32 -ceq 'CAFEBABE') {
        # Java class files share this magic, but their minor/major pair cannot be a small arch count.
        $architectureCount =
            ([uint32] $header[4] -shl 24) -bor
            ([uint32] $header[5] -shl 16) -bor
            ([uint32] $header[6] -shl 8) -bor
            [uint32] $header[7]
        $isFatMachO = $architectureCount -ge 1 -and $architectureCount -le 32
    }
    $isNativePayload =
        ($headerLength -ge 2 -and $magic.StartsWith('4D5A', [System.StringComparison]::Ordinal)) -or
        ($headerLength -ge 4 -and $magic32 -ceq '7F454C46') -or
        ($headerLength -ge 4 -and $magic32 -cin @(
                'FEEDFACE',
                'CEFAEDFE',
                'FEEDFACF',
                'CFFAEDFE',
                'BEBAFECA',
                'CAFEBABF',
                'BFBAFECA'
            )) -or
        $isFatMachO
    $hasZipEndRecord = $false
    # A valid trailing EOCD detects ZIPs with a preamble; the comment length avoids incidental PK bytes.
    for ($index = 0; $index -le $zipTrailerLength - 22; $index++) {
        if ($zipTrailer[$index] -eq 0x50 -and $zipTrailer[$index + 1] -eq 0x4b -and
            $zipTrailer[$index + 2] -eq 0x05 -and $zipTrailer[$index + 3] -eq 0x06) {
            $commentLength = $zipTrailer[$index + 20] -bor ($zipTrailer[$index + 21] -shl 8)
            if ($index + 22 + $commentLength -eq $zipTrailerLength) {
                $hasZipEndRecord = $true
                break
            }
        }
    }
    $isZipArchive = $hasZipEndRecord -or ($headerLength -ge 4 -and $magic32 -cin @(
        '504B0304',
        '504B0506',
        '504B0708'
    ))
    return [pscustomobject]@{
        sha256 = $hash
        isNativePayload = $isNativePayload
        isZipArchive = $isZipArchive
        isProprietaryPayload = $headerLength -ge 4 -and $magic32 -cin @(
            '00010000', '42534100', '42544458', '44445320', '54455333', '54455334'
        )
    }
}

<#
.SYNOPSIS
Requires one native stream's exact digest to appear in the approved native lookup.

.PARAMETER Stream
Readable native payload stream positioned at its first byte.

.PARAMETER DisplayPath
Stable archive-qualified path included in a failure.

.PARAMETER Context
Phrase describing where the unapproved payload was discovered.

.PARAMETER Extension
Lowercase supplied filename extension, retained as a fail-closed native signal.

.PARAMETER NativeByHash
All inventoried native entries, including payloads whose redistribution is blocked.

.PARAMETER ApprovedNativeByHash
Release-approved native entries keyed by exact payload SHA-256.

.PARAMETER MaximumBytes
Maximum bytes permitted before stream inspection aborts.

.OUTPUTS
The payload inspection result, for callers that also need content-based archive detection.

.NOTES
Consumes the stream from its current position to the end and leaves disposal to the caller. Native
identity comes from the supplied extension, binary signature, or an exact full-inventory hash.
#>
function Assert-ApprovedNativeStream {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Stream] $Stream,
        [Parameter(Mandatory = $true)]
        [string] $DisplayPath,
        [Parameter(Mandatory = $true)]
        [string] $Context,
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $Extension,
        [Parameter(Mandatory = $true)]
        [hashtable] $NativeByHash,
        [Parameter(Mandatory = $true)]
        [hashtable] $ApprovedNativeByHash,
        [long] $MaximumBytes = [long]::MaxValue
    )

    $inspection = Get-StreamPayloadInspection $Stream $MaximumBytes
    $isNativePayload =
        $Extension -cin $nativeLibraryExtensions -or
        $inspection.isNativePayload -or
        $NativeByHash.ContainsKey($inspection.sha256)
    if ($isNativePayload -and -not $ApprovedNativeByHash.ContainsKey($inspection.sha256)) {
        throw "Unapproved native payload $Context`: $DisplayPath ($($inspection.sha256))"
    }
    return $inspection
}

<#
.SYNOPSIS
Requires one native file's exact digest to appear in the approved native lookup.

.PARAMETER Path
Exact native library file to hash.

.PARAMETER DisplayPath
Stable repository- or release-relative path included in a failure.

.PARAMETER Context
Phrase describing where the unapproved payload was discovered.

.PARAMETER Extension
Lowercase supplied filename extension, retained as a fail-closed native signal.

.PARAMETER NativeByHash
All inventoried native entries, including payloads whose redistribution is blocked.

.PARAMETER ApprovedNativeByHash
Release-approved native entries keyed by exact payload SHA-256.

.OUTPUTS
The payload inspection result, for callers that also need content-based archive detection.

.NOTES
Opens and disposes its own file stream. Throws a terminating I/O or approval error.
#>
function Assert-ApprovedNativePayload {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [string] $DisplayPath,
        [Parameter(Mandatory = $true)]
        [string] $Context,
        [Parameter(Mandatory = $true)]
        [AllowEmptyString()]
        [string] $Extension,
        [Parameter(Mandatory = $true)]
        [hashtable] $NativeByHash,
        [Parameter(Mandatory = $true)]
        [hashtable] $ApprovedNativeByHash
    )

    $stream = [System.IO.File]::OpenRead($Path)
    try {
        return Assert-ApprovedNativeStream `
            $stream $DisplayPath $Context $Extension $NativeByHash $ApprovedNativeByHash
    }
    finally {
        $stream.Dispose()
    }
}

<#
.SYNOPSIS
Resolves one production project-artifact identity to its exact current reactor output.

.PARAMETER Source
Three-part Maven coordinate declared by a project-artifact manifest entry.

.PARAMETER ReleasePath
Canonical release-input path whose filename identifies packaging and classifier.

.PARAMETER ReactorVersion
Effective Maven version of the current reactor build.

.OUTPUTS
The exact existing reactor artifact path for the coordinate and canonical release filename.

.NOTES
Only required production artifacts are valid project-artifact identities. Throws when the source
is malformed, the release filename is noncanonical, or no current reactor output exists.
#>
function Resolve-ReactorProjectArtifact {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Source,
        [Parameter(Mandatory = $true)]
        [string] $ReleasePath,
        [Parameter(Mandatory = $true)]
        [string] $ReactorVersion
    )

    $coordinate = [regex]::Match(
        $Source,
        '^io\.github\.evildarkarchon:(jbsa|jbsa-cli):([^:]+)$',
        [System.Text.RegularExpressions.RegexOptions]::CultureInvariant
    )
    if (-not $coordinate.Success) {
        throw "Release input project artifact has an invalid source identity: $Source"
    }
    $artifactId = $coordinate.Groups[1].Value
    $version = $coordinate.Groups[2].Value
    if ($version -cne $ReactorVersion) {
        throw "Release input project artifact does not identify the current reactor version: $Source"
    }
    $releaseName = [System.IO.Path]::GetFileName($ReleasePath)
    $artifactMap = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::Ordinal
    )
    if ($artifactId -ceq 'jbsa') {
        $artifactMap.Add("jbsa-$ReactorVersion.jar", "jbsa/target/jbsa-$ReactorVersion.jar")
        $artifactMap.Add("jbsa-$ReactorVersion.pom", 'jbsa/.flattened-pom.xml')
        $artifactMap.Add(
            "jbsa-$ReactorVersion-sources.jar",
            "jbsa/target/jbsa-$ReactorVersion-sources.jar"
        )
        $artifactMap.Add(
            "jbsa-$ReactorVersion-javadoc.jar",
            "jbsa/target/jbsa-$ReactorVersion-javadoc.jar"
        )
    } else {
        $artifactMap.Add(
            "jbsa-cli-$ReactorVersion.jar",
            "jbsa-cli/target/jbsa-cli-$ReactorVersion.jar"
        )
    }
    if (-not $artifactMap.ContainsKey($releaseName)) {
        throw "Release input project artifact has a noncanonical path for its source: $ReleasePath"
    }
    $artifactPath = Join-Path $reactorRoot $artifactMap[$releaseName]
    if (-not (Test-Path -LiteralPath $artifactPath -PathType Leaf)) {
        throw "Release input project artifact has no current reactor output: $Source"
    }
    return [System.IO.Path]::GetFullPath($artifactPath)
}

<#
.SYNOPSIS
Recursively inspects one ZIP-compatible stream for prohibited or unapproved payload bytes.

.PARAMETER Stream
Seekable ZIP/JAR stream positioned at its first byte. The function leaves ownership to the caller.

.PARAMETER DisplayPath
Stable outer or archive-qualified path included in failures.

.PARAMETER NativeByHash
All inventoried native entries, including payloads whose redistribution is blocked.

.PARAMETER ApprovedNativeByHash
Release-approved native entries keyed by exact payload SHA-256.

.PARAMETER Depth
Current nested-archive depth used to enforce the bounded inspection limit.

.OUTPUTS
None.

.NOTES
Reads from the stream's current position and leaves the outer stream open. Nested streams are owned
and disposed here. Malformed, unsafe, over-limit, proprietary, or unapproved content throws a
terminating error.
#>
function Test-ZipArchiveContents {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.Stream] $Stream,
        [Parameter(Mandatory = $true)]
        [string] $DisplayPath,
        [Parameter(Mandatory = $true)]
        [hashtable] $NativeByHash,
        [Parameter(Mandatory = $true)]
        [hashtable] $ApprovedNativeByHash,
        [int] $Depth = 0
    )

    if ($Depth -gt $maximumArchiveDepth) {
        throw "Opaque nested archive exceeds the inspection depth limit: $DisplayPath"
    }
    $archive = [System.IO.Compression.ZipArchive]::new(
        $Stream,
        [System.IO.Compression.ZipArchiveMode]::Read,
        $true
    )
    try {
        $entryNames = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::OrdinalIgnoreCase
        )
        foreach ($entry in $archive.Entries) {
            $entryPath = $entry.FullName.Replace('\', '/')
            $entryIdentity = $entryPath.TrimEnd([char[]] @('/'))
            $entrySegments = $entryIdentity.Split('/')
            if ([string]::IsNullOrWhiteSpace($entryPath) -or
                [System.IO.Path]::IsPathRooted($entryPath) -or $entryPath.StartsWith('/') -or
                $entryPath -match '^[A-Za-z]:' -or $entryPath.Contains('//') -or
                $entrySegments -contains '.' -or $entrySegments -contains '..' -or
                $entrySegments -contains '' -or
                $entryPath.Contains(':')) {
                throw "Archive contains an unsafe entry path: $DisplayPath!/$entryPath"
            }
            if (-not $entryNames.Add($entryIdentity)) {
                throw "Archive contains a duplicate case-insensitive entry: $DisplayPath!/$entryPath"
            }
            if ($entry.Length -gt $maximumArchiveEntryBytes) {
                throw "Archive entry exceeds the inspection size limit: $DisplayPath!/$entryPath"
            }
            # Unix ZIP creators store st_mode in the upper half; S_IFMT 0xa000 identifies a link.
            $unixMode = ($entry.ExternalAttributes -shr 16) -band 0xffff
            if (($unixMode -band 0xf000) -eq 0xa000) {
                throw "Archive contains a symbolic-link entry: $DisplayPath!/$entryPath"
            }
            if ($entryPath.EndsWith('/')) {
                continue
            }

            $qualifiedPath = "$DisplayPath!/$entryPath"
            $extension = [System.IO.Path]::GetExtension($entryPath).ToLowerInvariant()
            if ($extension -in $proprietaryArchiveExtensions -or
                $entryPath -match '(^|/)(TES5Edit|fixtures/local|game-assets)(/|$)') {
                throw "Proprietary or local fixture material is forbidden in release inputs: $qualifiedPath"
            }
            $entryStream = $entry.Open()
            try {
                $inspection = Assert-ApprovedNativeStream `
                    $entryStream $qualifiedPath 'in archived release inputs' `
                    $extension $NativeByHash $ApprovedNativeByHash $maximumArchiveEntryBytes
            }
            finally {
                $entryStream.Dispose()
            }
            if ($inspection.isProprietaryPayload) {
                throw "Proprietary or local fixture material is forbidden in release inputs: $qualifiedPath"
            }
            if ($extension -in $nestedArchiveExtensions -or $inspection.isZipArchive) {
                $nestedStream = [System.IO.MemoryStream]::new([int] $entry.Length)
                try {
                    $entryStream = $entry.Open()
                    try {
                        $entryStream.CopyTo($nestedStream)
                    }
                    finally {
                        $entryStream.Dispose()
                    }
                    $nestedStream.Position = 0
                    Test-ZipArchiveContents `
                        $nestedStream $qualifiedPath $NativeByHash $ApprovedNativeByHash ($Depth + 1)
                }
                finally {
                    $nestedStream.Dispose()
                }
            }
        }
    }
    finally {
        $archive.Dispose()
    }
}

<#
.SYNOPSIS
Rejects tracked local/proprietary archives and unapproved committed native binaries.

.PARAMETER NativeByHash
All inventoried native entries, including payloads whose redistribution is blocked.

.PARAMETER ApprovedNativeByHash
Release-approved native entries keyed by exact payload SHA-256.

.OUTPUTS
None.

.NOTES
Throws a terminating error when Git enumeration fails or a tracked byte violates policy.
#>
function Test-TrackedRepositoryBytes {
    param(
        [Parameter(Mandatory = $true)]
        [hashtable] $NativeByHash,
        [Parameter(Mandatory = $true)]
        [hashtable] $ApprovedNativeByHash
    )

    # Git's normal output quotes non-ASCII filenames and splits embedded newlines. Capture raw UTF-8
    # NUL-delimited records so every index path resolves to the same filesystem name.
    $git = [System.Diagnostics.ProcessStartInfo]::new('git')
    foreach ($argument in @('-C', $reactorRoot, 'ls-files', '--stage', '-z')) {
        $git.ArgumentList.Add($argument)
    }
    $git.RedirectStandardOutput = $true
    $git.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $git.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($git)
    try {
        $indexRecords = $process.StandardOutput.ReadToEnd().Split([char]0, [StringSplitOptions]::RemoveEmptyEntries)
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw 'Unable to enumerate tracked repository files for the compliance audit.'
        }
    } finally { $process.Dispose() }
    $trackedFiles = @()
    $referenceEntries = @()
    foreach ($record in $indexRecords) {
        $separator = $record.IndexOf("`t")
        if ($separator -lt 0) { throw 'Malformed Git index record in compliance audit.' }
        $path = $record.Substring($separator + 1)
        $trackedFiles += $path
        if ($path -ceq 'TES5Edit' -or $path.StartsWith('TES5Edit/', [StringComparison]::OrdinalIgnoreCase)) {
            $referenceEntries += $record
        }
    }
    if ($referenceEntries.Count -ne 1 -or
        $referenceEntries[0] -cne "160000 fd1e36020b2b5b6217e553dc0038983146a2e2dd 0`tTES5Edit") {
        throw 'TES5Edit must remain the pinned read-only reference gitlink.'
    }
    $modulePath = @(& git config --file (Join-Path $reactorRoot '.gitmodules') --get-all submodule.TES5Edit.path)
    if ($LASTEXITCODE -ne 0) {
        throw 'TES5Edit reference path is missing from .gitmodules.'
    }
    $moduleUrl = @(& git config --file (Join-Path $reactorRoot '.gitmodules') --get-all submodule.TES5Edit.url)
    if ($LASTEXITCODE -ne 0 -or $modulePath.Count -ne 1 -or $modulePath[0] -cne 'TES5Edit' -or
        $moduleUrl.Count -ne 1 -or $moduleUrl[0] -cne 'https://github.com/TES5Edit/TES5Edit.git') {
        throw 'TES5Edit reference path or URL differs from the approved pinned reference.'
    }
    foreach ($relativePath in $trackedFiles) {
        $normalizedPath = $relativePath.Replace('\', '/')
        if ($normalizedPath -eq 'TES5Edit' -or $normalizedPath.StartsWith('TES5Edit/')) {
            continue
        }
        if ($normalizedPath.StartsWith('tests/fixtures/local/') -and
            $normalizedPath -notin @(
                'tests/fixtures/local/.gitkeep',
                'tests/fixtures/local/README.md'
            )) {
            throw "Proprietary or local fixture material is tracked: $normalizedPath"
        }
        $trackedPath = Join-Path $reactorRoot $relativePath
        if (-not (Test-Path -LiteralPath $trackedPath -PathType Leaf)) {
            # Index entries deleted in the working tree have no candidate bytes left to inspect.
            continue
        }
        $extension = [System.IO.Path]::GetExtension($normalizedPath).ToLowerInvariant()
        if ($extension -in $proprietaryArchiveExtensions -and
            -not $normalizedPath.StartsWith('tests/fixtures/synthetic/')) {
            throw "Proprietary or local fixture material is tracked outside the synthetic corpus: $normalizedPath"
        }
        $inspection = Assert-ApprovedNativePayload `
                $trackedPath $normalizedPath 'is tracked' `
                $extension $NativeByHash $ApprovedNativeByHash
        if ($inspection.isProprietaryPayload -and -not $normalizedPath.StartsWith('tests/fixtures/synthetic/')) {
            throw "Proprietary or local fixture material is tracked outside the synthetic corpus: $normalizedPath"
        }
        if ($extension -in $nestedArchiveExtensions -or $inspection.isZipArchive) {
            $archiveStream = [System.IO.File]::OpenRead($trackedPath)
            try {
                Test-ZipArchiveContents $archiveStream $normalizedPath $NativeByHash $ApprovedNativeByHash
            } finally { $archiveStream.Dispose() }
        }
    }
}

<#
.SYNOPSIS
Audits an optional release-input tree against prohibited content, native approval, and a manifest.

.PARAMETER Root
Exact directory containing candidate release inputs.

.PARAMETER ManifestPath
Optional exact schema-version-1 JSON manifest for every regular file below Root.

.PARAMETER NativeByHash
All inventoried native entries, including payloads whose redistribution is blocked.

.PARAMETER ApprovedNativeByHash
Release-approved native entries keyed by exact payload SHA-256.

.PARAMETER ApprovedDependencyByHash
Release-approved dependency entries keyed by exact artifact SHA-256.

.PARAMETER ReactorVersion
Effective Maven version used to resolve the current reactor artifact paths.

.OUTPUTS
None.

.NOTES
Throws a terminating error for missing inputs, unsafe or opaque archive content, unapproved native
bytes, malformed manifest evidence, missing files, or checksum disagreement.
#>
function Test-ReleaseInputs {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Root,
        [string] $ManifestPath,
        [Parameter(Mandatory = $true)]
        [hashtable] $NativeByHash,
        [Parameter(Mandatory = $true)]
        [hashtable] $ApprovedNativeByHash,
        [Parameter(Mandatory = $true)]
        [hashtable] $ApprovedDependencyByHash,
        [Parameter(Mandatory = $true)]
        [string] $ReactorVersion
    )

    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    if (-not (Test-Path -LiteralPath $resolvedRoot -PathType Container)) {
        throw "Release input root does not exist: $resolvedRoot"
    }
    $files = @(Get-ChildItem -LiteralPath $resolvedRoot -File -Recurse -Force)
    $releaseInputNames = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase
    )
    $fileHashes = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::Ordinal
    )
    foreach ($file in $files) {
        $relativePath = [System.IO.Path]::GetRelativePath($resolvedRoot, $file.FullName).Replace('\', '/')
        if (-not $releaseInputNames.Add($relativePath)) {
            throw "Release inputs contain a duplicate case-insensitive path: $relativePath"
        }
        $extension = $file.Extension.ToLowerInvariant()
        if ($extension -in $proprietaryArchiveExtensions -or
            $relativePath -match '(^|/)(TES5Edit|fixtures/local|game-assets)(/|$)') {
            throw "Proprietary or local fixture material is forbidden in release inputs: $relativePath"
        }
        $inspection = Assert-ApprovedNativePayload `
            $file.FullName $relativePath 'in release inputs' `
            $extension $NativeByHash $ApprovedNativeByHash
        if ($inspection.isProprietaryPayload) {
            throw "Proprietary or local fixture material is forbidden in release inputs: $relativePath"
        }
        $fileHashes[$relativePath] = $inspection.sha256
        if ($extension -in $nestedArchiveExtensions -or $inspection.isZipArchive) {
            $archiveStream = [System.IO.File]::OpenRead($file.FullName)
            try {
                Test-ZipArchiveContents `
                    $archiveStream $relativePath $NativeByHash $ApprovedNativeByHash
            }
            finally {
                $archiveStream.Dispose()
            }
        }
    }

    # An explicit manifest remains authoritative even after every staged file has been removed.
    if ($files.Count -eq 0 -and [string]::IsNullOrWhiteSpace($ManifestPath)) {
        return
    }
    if ([string]::IsNullOrWhiteSpace($ManifestPath)) {
        throw 'Ambiguous release inputs are forbidden: a non-empty input root requires -ReleaseInputManifest.'
    }
    $resolvedManifest = [System.IO.Path]::GetFullPath($ManifestPath)
    $manifest = Read-ComplianceInventory $resolvedManifest
    $manifestFiles = @{}
    foreach ($entry in @($manifest.entries)) {
        foreach ($property in @('path', 'sha256', 'kind', 'source')) {
            Assert-StringProperty $entry $property 'Release input manifest entry'
        }
        $normalizedPath = $entry.path.Replace('\', '/')
        if ([System.IO.Path]::IsPathRooted($normalizedPath) -or
            $normalizedPath.Split('/') -contains '..') {
            throw "Release input manifest path must be relative and non-traversing: $normalizedPath"
        }
        if ($entry.sha256 -cnotmatch $sha256Pattern) {
            throw "Release input manifest entry has an invalid lowercase SHA-256: $normalizedPath"
        }
        $allowedKinds = @(
            'project-artifact',
            'dependency',
            'native-container',
            'native-payload',
            'license',
            'notice',
            'release-notes',
            'sbom',
            'provenance',
            'checksum',
            'documentation'
        )
        if ($entry.kind -cnotin $allowedKinds) {
            throw "Release input manifest entry has an unsupported kind: $normalizedPath ($($entry.kind))"
        }
        if ($entry.kind -in @('dependency', 'native-container')) {
            if (-not $ApprovedDependencyByHash.ContainsKey($entry.sha256)) {
                throw "Release input dependency is not an approved inventory artifact: $normalizedPath"
            }
            $dependency = $ApprovedDependencyByHash[$entry.sha256]
            if ($entry.source -cne (Get-DependencyKey $dependency)) {
                throw "Release input dependency source disagrees with its inventory identity: $normalizedPath"
            }
            if ($entry.kind -ceq 'native-container' -and -not $dependency.containsNativeBytes) {
                throw "Release input native-container does not contain inventoried native bytes: $normalizedPath"
            }
        } elseif ($entry.kind -ceq 'native-payload') {
            if (-not $ApprovedNativeByHash.ContainsKey($entry.sha256) -or
                $entry.source -cne $ApprovedNativeByHash[$entry.sha256].name) {
                throw "Release input native payload is not reconciled with its approved inventory: $normalizedPath"
            }
        } elseif ($entry.kind -ceq 'project-artifact') {
            $reactorArtifact = Resolve-ReactorProjectArtifact `
                $entry.source $normalizedPath $ReactorVersion
            $reactorArtifactHash = Get-LowercaseSha256 $reactorArtifact
            if ($entry.sha256 -cne $reactorArtifactHash) {
                throw "Release input project artifact does not match the reactor output: $normalizedPath"
            }
        } else {
            $sourcePath = [System.IO.Path]::GetFullPath((Join-Path $reactorRoot $entry.source))
            if (-not $sourcePath.StartsWith(
                    $reactorRoot + [System.IO.Path]::DirectorySeparatorChar,
                    [System.StringComparison]::OrdinalIgnoreCase
                ) -or -not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
                throw "Release input evidence source must be an existing repository file: $normalizedPath"
            }
            if ($entry.sha256 -cne (Get-LowercaseSha256 $sourcePath)) {
                throw "Release input evidence does not match its declared source bytes: $normalizedPath"
            }
        }
        if ($manifestFiles.ContainsKey($normalizedPath)) {
            throw "Duplicate release input manifest path: $normalizedPath"
        }
        $manifestFiles[$normalizedPath] = $entry
    }

    foreach ($file in $files) {
        $relativePath = [System.IO.Path]::GetRelativePath($resolvedRoot, $file.FullName).Replace('\', '/')
        if (-not $manifestFiles.ContainsKey($relativePath)) {
            throw "Unaccounted release input: $relativePath"
        }
        $actualHash = $fileHashes[$relativePath]
        if ($actualHash -cne $manifestFiles[$relativePath].sha256) {
            throw "Release input checksum mismatch: $relativePath"
        }
    }
    foreach ($relativePath in $manifestFiles.Keys) {
        if (-not (Test-Path -LiteralPath (Join-Path $resolvedRoot $relativePath) -PathType Leaf)) {
            throw "Release input manifest names a missing file: $relativePath"
        }
    }
}

<#
.SYNOPSIS
Tests whether one CycloneDX component has the exact identity of one dependency entry.

.PARAMETER Component
CycloneDX component with group, name, version, and package URL fields.

.PARAMETER Entry
Dependency inventory entry to compare, including an optional classifier.

.OUTPUTS
True when the component and inventory identity match; otherwise false.

.NOTES
Malformed component or inventory property access is intentionally propagated.
#>
function Test-SbomComponentMatchesDependency {
    param(
        [Parameter(Mandatory = $true)]
        [object] $Component,
        [Parameter(Mandatory = $true)]
        [object] $Entry
    )

    if ($Component.group -cne $Entry.groupId -or $Component.name -cne $Entry.artifactId -or
        $Component.version -cne $Entry.version) {
        return $false
    }
    $componentPurl = [string] $Component.purl
    if ($null -eq $Entry.classifier) {
        return $componentPurl -notmatch '[?&]classifier='
    }
    $escapedClassifier = [regex]::Escape([string] $Entry.classifier)
    return $componentPurl -match "[?&]classifier=$escapedClassifier(?:&|$)"
}

<#
.SYNOPSIS
Verifies the generated aggregate CycloneDX document and reconciles its external dependency set.

.PARAMETER DependencyInventory
Parsed dependency inventory whose approved entries must exactly equal the SBOM's external set.

.PARAMETER SbomPath
Exact CycloneDX JSON document to inspect.

.OUTPUTS
None.

.NOTES
Throws a terminating error when the SBOM is absent, malformed, the wrong format, or differs from
the complete approved external dependency inventory.
#>
function Test-GeneratedSbom {
    param(
        [Parameter(Mandatory = $true)]
        [object] $DependencyInventory,
        [Parameter(Mandatory = $true)]
        [string] $SbomPath
    )

    if (-not (Test-Path -LiteralPath $SbomPath -PathType Leaf)) {
        throw "Missing aggregate CycloneDX SBOM: $SbomPath"
    }
    $sbom = Get-Content -Raw -LiteralPath $SbomPath | ConvertFrom-Json -Depth 100
    if ($sbom.bomFormat -cne 'CycloneDX' -or $sbom.specVersion -cne '1.6') {
        throw 'The aggregate SBOM must be CycloneDX 1.6 JSON.'
    }
    $approvedEntries = @($DependencyInventory.entries | Where-Object {
            $_.redistribution.approved
        })
    $components = @($sbom.components)
    foreach ($entry in $approvedEntries) {
        $matchingComponents = @($sbom.components | Where-Object {
                Test-SbomComponentMatchesDependency $_ $entry
            })
        if ($matchingComponents.Count -eq 0) {
            throw "Aggregate SBOM is missing approved release dependency: $(Get-DependencyKey $entry)"
        }
        foreach ($component in $matchingComponents) {
            $hashProperty = $component.PSObject.Properties['hashes']
            $hashes = if ($null -eq $hashProperty) { @() } else { @($hashProperty.Value | Where-Object { $_.alg -ceq 'SHA-256' }) }
            if ($hashes.Count -ne 1 -or $hashes[0].content -cne $entry.sha256) {
                throw "Aggregate SBOM dependency SHA-256 disagrees with the approved inventory: $(Get-DependencyKey $entry)"
            }
        }
    }
    foreach ($component in $components) {
        $reactorType = if ($component.name -ceq 'jbsa-parent') { 'pom' } else { 'jar' }
        $reactorPurl = "pkg:maven/io.github.evildarkarchon/$($component.name)@${ReactorVersion}?type=$reactorType"
        if ($component.group -ceq 'io.github.evildarkarchon' -and
            $component.name -cin @('jbsa-parent', 'jbsa', 'jbsa-cli') -and
            $component.version -ceq $ReactorVersion -and
            $component.purl -ceq $reactorPurl) {
            continue
        }
        $matchingEntries = @($approvedEntries | Where-Object {
                Test-SbomComponentMatchesDependency $component $_
            })
        if ($matchingEntries.Count -eq 0) {
            throw "Aggregate SBOM contains an unapproved or uninventoried external component: $($component.group):$($component.name):$($component.version)"
        }
    }
}

$dependencyInventory = Read-ComplianceInventory $dependencyInventoryPath
$nativeInventory = Read-ComplianceInventory $nativeInventoryPath
$dependencyLookup = Get-ValidatedDependencyLookup $dependencyInventory
$nativeByHash = Get-ValidatedNativeHashLookup $nativeInventory $dependencyLookup
$approvedNativeByHash = @{}
foreach ($hash in $nativeByHash.Keys) {
    if ($nativeByHash[$hash].redistribution.approved) {
        $approvedNativeByHash[$hash] = $nativeByHash[$hash]
    }
}
$approvedDependencyByHash = @{}
foreach ($entry in @($dependencyInventory.entries | Where-Object {
            $_.redistribution.approved
        })) {
    $approvedDependencyByHash[$entry.sha256] = $entry
}
if ([string]::IsNullOrWhiteSpace($ReactorVersion)) {
    $ReactorVersion = Get-DeclaredReactorVersion (Join-Path $reactorRoot 'pom.xml')
}

Test-ProductDependencies $dependencyLookup
Test-TrackedRepositoryBytes $nativeByHash $approvedNativeByHash

$notices = New-ThirdPartyNoticesText $dependencyInventory $nativeInventory
$committedNotices = (Get-Content -Raw -LiteralPath $committedNoticesPath).Replace("`r`n", "`n")
if ($committedNotices -cne $notices) {
    throw 'THIRD-PARTY-NOTICES.md is stale; regenerate it from the compliance inventories.'
}
New-Item -ItemType Directory -Path $complianceOutput -Force | Out-Null
[System.IO.File]::WriteAllText($generatedNoticesPath, $notices, [System.Text.UTF8Encoding]::new($false))
$releaseNotes = (Get-Content -Raw -LiteralPath $committedReleaseNotesPath).Replace("`r`n", "`n")
if ($releaseNotes -cnotmatch 'fd1e36020b2b5b6217e553dc0038983146a2e2dd' -or
    $releaseNotes -cnotmatch 'independently authored') {
    throw 'RELEASE-NOTES.md must retain independent-project and pinned Reference Snapshot attribution.'
}
[System.IO.File]::WriteAllText(
    $generatedReleaseNotesPath,
    $releaseNotes,
    [System.Text.UTF8Encoding]::new($false)
)

if (-not [string]::IsNullOrWhiteSpace($ReleaseInputRoot)) {
    Test-ReleaseInputs `
        $ReleaseInputRoot $ReleaseInputManifest $nativeByHash $approvedNativeByHash `
        $approvedDependencyByHash $ReactorVersion
} elseif (-not [string]::IsNullOrWhiteSpace($ReleaseInputManifest)) {
    throw '-ReleaseInputManifest requires -ReleaseInputRoot.'
}

if ($RequireGeneratedArtifacts) {
    $sbomPath = if ([string]::IsNullOrWhiteSpace($GeneratedSbomPath)) {
        Join-Path $complianceOutput 'jbsa.cdx.json'
    } else {
        [System.IO.Path]::GetFullPath($GeneratedSbomPath)
    }
    Test-GeneratedSbom $dependencyInventory $sbomPath
} elseif (-not [string]::IsNullOrWhiteSpace($GeneratedSbomPath)) {
    throw '-GeneratedSbomPath requires -RequireGeneratedArtifacts.'
}

Write-Output 'Compliance verification passed: policy, inventories, notices, tracked bytes, and release inputs are authorized.'
