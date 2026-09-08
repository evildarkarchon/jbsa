<# .SYNOPSIS Corroborates the two-entry TES3 walking-slice fixture through the independent-validator harness. #>
param(
    [Parameter(Mandatory)][string]$InputPath,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')
$implementation = Join-Path $PSScriptRoot 'validate-tes3-wire.ps1'
$implementationDigest = (Get-FileHash -LiteralPath $implementation -Algorithm SHA256).Hash.ToLowerInvariant()
$executable = (Get-Command pwsh).Source
$tool = [ordered]@{
    identity = 'jbsa-project-independent-tes3-wire-scanner'
    path = $executable
    sha256 = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant()
    adapter_version = '1'
    kind = 'archive'
    independent = $true
    derived_from_reference = $false
    implementation = $implementation
    implementation_sha256 = $implementationDigest
    scope = 'canonical ASCII stored unshared TES3; independent of product parser and Reference Snapshot source'
}
$expected = @'
{"family":"tes3","entries":[{"name":"meshes\\a.nif","name_hash":"54b7713268731608","size":4,"payload_sha256":"79d28ed1fdc9d6c368599145ef2c837668164b86ba17caca768b522fad97fff5"},{"name":"sound\\b.wav","name_hash":"bb9745d06e756f17","size":5,"payload_sha256":"ff5d8507b6a72bee2debce2c0054798deaccdc5d8a1b945b6280ce8aa9cba52e"}]}
'@ | ConvertFrom-Json -AsHashtable
$result = Invoke-ConformanceValidator -Tool $tool -InputPath $InputPath `
    -InputSha256 (Get-FileHash -LiteralPath $InputPath -Algorithm SHA256).Hash.ToLowerInvariant() `
    -ExpectedProjection $expected -WorkingDirectory $WorkingDirectory -EvidenceDirectory $EvidenceDirectory `
    -Arguments @('-NoLogo', '-NoProfile', '-NonInteractive', '-File', $implementation, '-InputPath', $InputPath)
if ((Get-FileHash -LiteralPath $implementation -Algorithm SHA256).Hash.ToLowerInvariant() -cne $implementationDigest) {
    $result.result = 'INVALID'
    $result.error = 'Independent validator implementation changed during observation'
}
[IO.Directory]::CreateDirectory($EvidenceDirectory) | Out-Null
$json = $result | ConvertTo-Json -Depth 100
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'observation.json'), $json)
Write-Output $json
if ($result.result -ne 'PASS') { exit 1 }
