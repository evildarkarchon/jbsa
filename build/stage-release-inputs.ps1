<#
.SYNOPSIS
Stages current public Gradle artifacts and compliance evidence with a deterministic byte manifest.

.PARAMETER ReactorVersion
Effective build version used in artifact paths and project source identities.

.PARAMETER BuildLayoutManifest
Required schema-version-1 Gradle build-layout manifest that supplies generated artifact paths.

.NOTES
Owns only jbsa-dist/target/release-inputs and its sibling manifest. Missing inputs fail before
replacing staging. This prepares packaging inputs; it does not qualify or publish a release.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._+-]*$')]
    [string] $ReactorVersion,
    [Parameter(Mandatory = $true)]
    [string] $BuildLayoutManifest
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$reactorRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targetRoot = Join-Path $reactorRoot 'jbsa-dist/target'
$stageRoot = Join-Path $targetRoot 'release-inputs'
$manifestPath = Join-Path $targetRoot 'release-inputs.json'

function Get-GradleReleaseSources {
    <#
    .SYNOPSIS
    Resolves the canonical staging sources from a validated Gradle build-layout manifest.

    .PARAMETER Path
    Exact schema-version-1 build-layout manifest produced by Gradle.

    .OUTPUTS
    A hashtable from staging source role to a repository-relative, contained generated path.

    .NOTES
    Throws a terminating error when the manifest is absent, malformed, duplicated, incomplete, or
    names a path outside the repository.
    #>
    param([Parameter(Mandatory = $true)][string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing build-layout manifest: $Path"
    }
    $layout = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json -Depth 100
    if ($layout.schemaVersion -ne 1 -or $layout.rootProject -cne 'jbsa-parent' -or $null -eq $layout.outputs) {
        throw 'The build-layout manifest must use schema 1 and the jbsa-parent root.'
    }
    $outputsById = @{}
    foreach ($output in @($layout.outputs)) {
        $id = [string] $output.id
        $relativePath = ([string] $output.path).Replace('\', '/')
        if ([string]::IsNullOrWhiteSpace($id) -or [string]::IsNullOrWhiteSpace($relativePath)) {
            throw 'Build-layout outputs require non-empty ids and paths.'
        }
        if ($outputsById.ContainsKey($id)) {
            throw "Duplicate build-layout output id: $id"
        }
        $segments = @($relativePath -split '/')
        if ([IO.Path]::IsPathFullyQualified($relativePath) -or $relativePath.StartsWith('/') -or
            $segments.Count -eq 0 -or @($segments | Where-Object { $_ -in @('', '.', '..') }).Count -gt 0) {
            throw "Build-layout output path must be relative and non-traversing: $relativePath"
        }
        $resolved = [IO.Path]::GetFullPath((Join-Path $reactorRoot $relativePath))
        if (-not $resolved.StartsWith($reactorRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Build-layout output escapes the repository: $relativePath"
        }
        $outputsById[$id] = $relativePath
    }
    $requiredOutputs = [ordered]@{
        libraryBinary = 'library-binary'
        libraryConsumerPom = 'library-consumer-pom'
        librarySources = 'library-sources'
        libraryJavadoc = 'library-javadoc'
        cliBinary = 'cli-binary'
        generatedThirdPartyNotices = 'generated-third-party-notices'
        generatedReleaseNotes = 'generated-release-notes'
        aggregateSbom = 'aggregate-sbom'
        runtimeDependencies = 'runtime-dependencies'
    }
    $sources = @{}
    foreach ($role in $requiredOutputs.Keys) {
        $id = $requiredOutputs[$role]
        if (-not $outputsById.ContainsKey($id)) {
            throw "Build-layout manifest is missing required staging output: $id"
        }
        $sources[$role] = $outputsById[$id]
    }
    return $sources
}

$releaseSources = Get-GradleReleaseSources -Path $BuildLayoutManifest
$inputs = @(
    @{ path = "jbsa-$ReactorVersion.jar"; source = $releaseSources.libraryBinary; artifact = 'jbsa'; kind = 'project-artifact' },
    @{ path = "jbsa-$ReactorVersion.pom"; source = $releaseSources.libraryConsumerPom; artifact = 'jbsa'; kind = 'project-artifact' },
    @{ path = "jbsa-$ReactorVersion-sources.jar"; source = $releaseSources.librarySources; artifact = 'jbsa'; kind = 'project-artifact' },
    @{ path = "jbsa-$ReactorVersion-javadoc.jar"; source = $releaseSources.libraryJavadoc; artifact = 'jbsa'; kind = 'project-artifact' },
    @{ path = "jbsa-cli-$ReactorVersion.jar"; source = $releaseSources.cliBinary; artifact = 'jbsa-cli'; kind = 'project-artifact' },
    @{ path = 'LICENSE'; source = 'LICENSE'; kind = 'license' },
    @{ path = 'NOTICE'; source = 'NOTICE'; kind = 'notice' },
    @{ path = 'THIRD-PARTY-NOTICES.md'; source = $releaseSources.generatedThirdPartyNotices; kind = 'notice' },
    @{ path = 'RELEASE-NOTES.md'; source = $releaseSources.generatedReleaseNotes; kind = 'release-notes' },
    @{ path = 'jbsa.cdx.json'; source = $releaseSources.aggregateSbom; kind = 'sbom' },
    @{ path = 'licenses/LWJGL-3.4.3.txt'; source = 'compliance/licenses/LWJGL-3.4.3.txt'; kind = 'license' },
    @{ path = 'licenses/LZ4-1.10.0.txt'; source = 'compliance/licenses/LZ4-1.10.0.txt'; kind = 'license' },
    @{ path = 'jbsa.ps1'; source = 'build/windows-runtime/jbsa.ps1'; kind = 'provenance' },
    @{ path = 'launch-policy.json'; source = 'build/windows-runtime/launch-policy.json'; kind = 'provenance' }
)
$inventory = Get-Content -Raw -LiteralPath (Join-Path $reactorRoot 'compliance/dependency-inventory.json') | ConvertFrom-Json
foreach ($dependency in @($inventory.entries | Where-Object { $_.groupId -eq 'org.lwjgl' })) {
    if (-not $dependency.redistribution.approved) { throw "Runtime dependency is not qualified: $($dependency.artifactId)" }
    $classifierSuffix = if ($null -eq $dependency.classifier) { '' } else { "-$($dependency.classifier)" }
    $filename = "$($dependency.artifactId)-$($dependency.version)$classifierSuffix.jar"
    $source = "$($releaseSources.runtimeDependencies)/$filename"
    $sourcePath = Join-Path $reactorRoot $source
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) { throw "Missing release input source: $source" }
    # Validate Gradle-resolved bytes before replacing prior staging; dependency caches are not assembly inputs.
    if ((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $dependency.sha256) {
        throw "Runtime checksum mismatch: $filename"
    }
    $inputs += @{ path = "lib/$filename"; source = $source; kind = $(if ($dependency.containsNativeBytes) { 'native-container' } else { 'dependency' }); identity = ('{0}:{1}:{2}:{3}:{4}' -f $dependency.groupId, $dependency.artifactId, $dependency.packaging, $dependency.classifier, $dependency.version) }
}
foreach ($stagedInput in $inputs) {
    if (-not (Test-Path -LiteralPath (Join-Path $reactorRoot $stagedInput.source) -PathType Leaf)) {
        throw "Missing release input source: $($stagedInput.source)"
    }
}
# Refuse redirected staging before deleting or copying: a junction must not redirect owned cleanup.
$ownedPaths = @((Join-Path $reactorRoot 'jbsa-dist'), $targetRoot, $stageRoot, $manifestPath)
if (Test-Path -LiteralPath $stageRoot) {
    $ownedPaths += @(Get-ChildItem -LiteralPath $stageRoot -Recurse -Force | ForEach-Object FullName)
}
foreach ($ownedPath in $ownedPaths) {
    if ((Test-Path -LiteralPath $ownedPath) -and
        ((Get-Item -LiteralPath $ownedPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Release staging must not contain a reparse point: $ownedPath"
    }
}
$resolvedStage = [IO.Path]::GetFullPath($stageRoot)
$expectedStage = [IO.Path]::GetFullPath((Join-Path $reactorRoot 'jbsa-dist/target/release-inputs'))
if ($resolvedStage -cne $expectedStage -or -not $resolvedStage.StartsWith($reactorRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to replace staging outside the build root: $resolvedStage"
}
if (Test-Path -LiteralPath $resolvedStage) { Remove-Item -LiteralPath $resolvedStage -Recurse -Force }
if (Test-Path -LiteralPath $manifestPath) { Remove-Item -LiteralPath $manifestPath -Force }
New-Item -ItemType Directory -Path $resolvedStage -Force | Out-Null
$entries = foreach ($stagedInput in $inputs) {
    $destination = Join-Path $resolvedStage $stagedInput.path
    New-Item -ItemType Directory -Path (Split-Path $destination) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $reactorRoot $stagedInput.source) -Destination $destination
    $sourceIdentity = if ($stagedInput.kind -eq 'project-artifact') {
        "io.github.evildarkarchon:$($stagedInput.artifact):$ReactorVersion"
    } elseif ($stagedInput.ContainsKey('identity')) { $stagedInput.identity }
    else { $stagedInput.source }
    [ordered]@{
        path = $stagedInput.path
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash.ToLowerInvariant()
        kind = $stagedInput.kind
        source = $sourceIdentity
    }
}
$manifest = [ordered]@{ schemaVersion = 1; entries = @($entries) } | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText($manifestPath, $manifest.Replace("`r`n", "`n") + "`n", [Text.UTF8Encoding]::new($false))
Write-Output "Staged $($entries.Count) current release inputs for $ReactorVersion."
