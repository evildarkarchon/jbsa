<# .SYNOPSIS Corroborates the two-entry TES4 slice through the independent-validator harness. #>
param(
    [Parameter(Mandatory)][string]$InputPath,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')
$implementation = Join-Path $PSScriptRoot 'validate-bsa-wire.py'
$implementationDigest = (Get-FileHash -LiteralPath $implementation -Algorithm SHA256).Hash.ToLowerInvariant()
$executable = (Get-Command python).Source
$tool = [ordered]@{
    identity = 'jbsa-project-independent-bsa-067-wire-scanner'
    path = $executable
    sha256 = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant()
    adapter_version = '1'
    kind = 'archive'
    independent = $true
    derived_from_reference = $false
    implementation = $implementation
    implementation_sha256 = $implementationDigest
    scope = 'ASCII named unshared TES4 stored/zlib; independent of product and Reference Snapshot source'
}
$expected = @'
{"family":"bsa-067","entries":[{"name":"meshes\\a.nif","size":1024,"payload_sha256":"6ab72eeb9e77b07540897e0c8d6d23ec8eef0f8c3a47e1b3f4e93443d9536bed"},{"name":"meshes\\b.nif","size":4,"payload_sha256":"3d1f57c984978ef98a18378c8166c1cb8ede02c03eeb6aee7e2f121dfeee3e56"}]}
'@ | ConvertFrom-Json -AsHashtable
$result = Invoke-ConformanceValidator -Tool $tool -InputPath $InputPath `
    -InputSha256 (Get-FileHash -LiteralPath $InputPath -Algorithm SHA256).Hash.ToLowerInvariant() `
    -ExpectedProjection $expected -WorkingDirectory $WorkingDirectory -EvidenceDirectory $EvidenceDirectory `
    -Arguments @($implementation, $InputPath)
if ((Get-FileHash -LiteralPath $implementation -Algorithm SHA256).Hash.ToLowerInvariant() -cne $implementationDigest) {
    $result.result = 'INVALID'
    $result.error = 'Independent validator implementation changed during observation'
}
[IO.Directory]::CreateDirectory($EvidenceDirectory) | Out-Null
$json = $result | ConvertTo-Json -Depth 100
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'observation.json'), $json)
Write-Output $json
if ($result.result -ne 'PASS') { exit 1 }
