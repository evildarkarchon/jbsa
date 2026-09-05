<#
.SYNOPSIS
Stages current public reactor artifacts and compliance evidence with a deterministic byte manifest.

.PARAMETER ReactorVersion
Effective Maven revision used in artifact paths and project source identities.

.NOTES
Owns only jbsa-dist/target/release-inputs and its sibling manifest. Missing inputs fail before
replacing staging. This prepares packaging inputs; it does not qualify or publish a release.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._+-]*$')]
    [string] $ReactorVersion
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$reactorRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targetRoot = Join-Path $reactorRoot 'jbsa-dist/target'
$stageRoot = Join-Path $targetRoot 'release-inputs'
$manifestPath = Join-Path $targetRoot 'release-inputs.json'
$inputs = @(
    @{ path = "jbsa-$ReactorVersion.jar"; source = "jbsa/target/jbsa-$ReactorVersion.jar"; artifact = 'jbsa'; kind = 'project-artifact' },
    @{ path = "jbsa-$ReactorVersion.pom"; source = 'jbsa/.flattened-pom.xml'; artifact = 'jbsa'; kind = 'project-artifact' },
    @{ path = "jbsa-$ReactorVersion-sources.jar"; source = "jbsa/target/jbsa-$ReactorVersion-sources.jar"; artifact = 'jbsa'; kind = 'project-artifact' },
    @{ path = "jbsa-$ReactorVersion-javadoc.jar"; source = "jbsa/target/jbsa-$ReactorVersion-javadoc.jar"; artifact = 'jbsa'; kind = 'project-artifact' },
    @{ path = "jbsa-cli-$ReactorVersion.jar"; source = "jbsa-cli/target/jbsa-cli-$ReactorVersion.jar"; artifact = 'jbsa-cli'; kind = 'project-artifact' },
    @{ path = 'LICENSE'; source = 'LICENSE'; kind = 'license' },
    @{ path = 'NOTICE'; source = 'NOTICE'; kind = 'notice' },
    @{ path = 'THIRD-PARTY-NOTICES.md'; source = 'target/compliance/THIRD-PARTY-NOTICES.md'; kind = 'notice' },
    @{ path = 'RELEASE-NOTES.md'; source = 'target/compliance/RELEASE-NOTES.md'; kind = 'release-notes' },
    @{ path = 'jbsa.cdx.json'; source = 'target/compliance/jbsa.cdx.json'; kind = 'sbom' }
)
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
    throw "Refusing to replace staging outside the reactor: $resolvedStage"
}
if (Test-Path -LiteralPath $resolvedStage) { Remove-Item -LiteralPath $resolvedStage -Recurse -Force }
if (Test-Path -LiteralPath $manifestPath) { Remove-Item -LiteralPath $manifestPath -Force }
New-Item -ItemType Directory -Path $resolvedStage -Force | Out-Null
$entries = foreach ($stagedInput in $inputs) {
    $destination = Join-Path $resolvedStage $stagedInput.path
    Copy-Item -LiteralPath (Join-Path $reactorRoot $stagedInput.source) -Destination $destination
    $sourceIdentity = if ($stagedInput.kind -eq 'project-artifact') {
        "io.github.evildarkarchon:$($stagedInput.artifact):$ReactorVersion"
    } else { $stagedInput.source }
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
