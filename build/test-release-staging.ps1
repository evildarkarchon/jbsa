<#
.SYNOPSIS
Checks release-input staging byte identity, version selection, stale-file removal, and missing inputs.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) "jbsa-staging-test-$([guid]::NewGuid().ToString('N'))"
try {
    New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'build') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'stage-release-inputs.ps1') -Destination (Join-Path $fixtureRoot 'build')
    $sources = @('jbsa/target/jbsa-2.3.4.jar', 'jbsa/.flattened-pom.xml', 'jbsa/target/jbsa-2.3.4-sources.jar', 'jbsa/target/jbsa-2.3.4-javadoc.jar', 'jbsa-cli/target/jbsa-cli-2.3.4.jar', 'LICENSE', 'NOTICE', 'target/compliance/THIRD-PARTY-NOTICES.md', 'target/compliance/RELEASE-NOTES.md', 'target/compliance/jbsa.cdx.json')
    foreach ($source in $sources) {
        $path = Join-Path $fixtureRoot $source
        New-Item -ItemType Directory -Path (Split-Path $path) -Force | Out-Null
        [IO.File]::WriteAllBytes($path, [Text.Encoding]::UTF8.GetBytes("fixture bytes for $source"))
    }
    $runtimeSource = 'jbsa-dist/target/runtime-dependencies/lwjgl-3.4.3.jar'
    $runtimePath = Join-Path $fixtureRoot $runtimeSource
    New-Item -ItemType Directory -Path (Split-Path $runtimePath) -Force | Out-Null
    [IO.File]::WriteAllText($runtimePath, 'pinned runtime')
    New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'compliance') -Force | Out-Null
    $inventory = @{ entries = @(@{ groupId = 'org.lwjgl'; artifactId = 'lwjgl'; version = '3.4.3'; classifier = $null; packaging = 'jar'; containsNativeBytes = $false; sha256 = (Get-FileHash $runtimePath).Hash.ToLowerInvariant(); redistribution = @{ approved = $true } }) }
    $inventory | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $fixtureRoot 'compliance/dependency-inventory.json')
    foreach ($source in @('compliance/licenses/LWJGL-3.4.3.txt', 'compliance/licenses/LZ4-1.10.0.txt', 'build/windows-runtime/jbsa.ps1', 'build/windows-runtime/launch-policy.json')) {
        $path = Join-Path $fixtureRoot $source
        New-Item -ItemType Directory -Path (Split-Path $path) -Force | Out-Null
        [IO.File]::WriteAllText($path, "fixture bytes for $source")
    }
    $script = Join-Path $fixtureRoot 'build/stage-release-inputs.ps1'
    & pwsh -NoProfile -File $script -ReactorVersion 2.3.4
    if ($LASTEXITCODE -ne 0) { throw 'Staging failed with complete candidate inputs.' }
    $manifestPath = Join-Path $fixtureRoot 'jbsa-dist/target/release-inputs.json'
    $firstManifest = [IO.File]::ReadAllBytes($manifestPath)
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ($manifest.entries.Count -ne 15) { throw 'Staging did not account for all project, runtime and launch inputs.' }
    foreach ($entry in $manifest.entries) {
        $path = Join-Path $fixtureRoot "jbsa-dist/target/release-inputs/$($entry.path)"
        if ((Get-FileHash -LiteralPath $path).Hash.ToLowerInvariant() -cne $entry.sha256) { throw 'Staged bytes differ from the manifest.' }
        $source = if ($entry.kind -eq 'dependency') { $runtimeSource }
            elseif ($entry.path -eq 'jbsa-2.3.4.pom') { 'jbsa/.flattened-pom.xml' }
            elseif ($entry.path -like 'jbsa-cli-*.jar') { "jbsa-cli/target/$($entry.path)" }
            elseif ($entry.path -like 'jbsa-*.jar') { "jbsa/target/$($entry.path)" }
            else { $entry.source }
        if ((Get-FileHash -LiteralPath (Join-Path $fixtureRoot $source)).Hash.ToLowerInvariant() -cne $entry.sha256) { throw 'Staging copied bytes from the wrong source.' }
    }
    $stalePath = Join-Path $fixtureRoot 'jbsa-dist/target/release-inputs/stale.bin'
    Set-Content -LiteralPath $stalePath -Value 'old build'
    & pwsh -NoProfile -File $script -ReactorVersion 2.3.4
    if ($LASTEXITCODE -ne 0 -or (Test-Path -LiteralPath $stalePath)) { throw 'Restaging retained obsolete bytes.' }
    if ([Convert]::ToHexString($firstManifest) -cne [Convert]::ToHexString([IO.File]::ReadAllBytes($manifestPath))) { throw 'Staging manifest is not deterministic.' }
    [IO.File]::WriteAllText($runtimePath, 'tampered runtime')
    $failure = & pwsh -NoProfile -File $script -ReactorVersion 2.3.4 2>&1
    if ($LASTEXITCODE -eq 0 -or ($failure -join "`n") -notmatch 'Runtime checksum mismatch') { throw 'Changed runtime bytes did not fail staging.' }
    if ([Convert]::ToHexString($firstManifest) -cne [Convert]::ToHexString([IO.File]::ReadAllBytes($manifestPath))) { throw 'Failed runtime validation modified prior staging.' }
    [IO.File]::WriteAllText($runtimePath, 'pinned runtime')
    $inventory.entries[0].redistribution.approved = $false
    $inventory | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $fixtureRoot 'compliance/dependency-inventory.json')
    $failure = & pwsh -NoProfile -File $script -ReactorVersion 2.3.4 2>&1
    if ($LASTEXITCODE -eq 0 -or ($failure -join "`n") -notmatch 'Runtime dependency is not qualified') { throw 'An unqualified runtime did not fail staging.' }
    $inventory.entries[0].redistribution.approved = $true
    $inventory | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $fixtureRoot 'compliance/dependency-inventory.json')
    Remove-Item -LiteralPath (Join-Path $fixtureRoot 'jbsa/target/jbsa-2.3.4.jar')
    $failure = & pwsh -NoProfile -File $script -ReactorVersion 2.3.4 2>&1
    if ($LASTEXITCODE -eq 0 -or ($failure -join "`n") -notmatch 'Missing release input source') { throw 'A missing required artifact did not fail staging.' }
    Write-Output 'Release staging regression checks passed.'
}
finally {
    $resolvedRoot = [IO.Path]::GetFullPath($fixtureRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing cleanup outside temp: $resolvedRoot" }
    if (Test-Path -LiteralPath $resolvedRoot) { Remove-Item -LiteralPath $resolvedRoot -Recurse -Force }
}
