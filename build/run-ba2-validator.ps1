<# .SYNOPSIS Corroborates the two-entry General BA2 slice through the independent-validator harness. #>
param(
    [Parameter(Mandatory)][string]$InputPath,
    [Parameter(Mandatory)][string]$WorkingDirectory,
    [Parameter(Mandatory)][string]$EvidenceDirectory
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')
$implementation = Join-Path $PSScriptRoot 'validate-ba2-wire.py'
$implementationDigest = (Get-FileHash -LiteralPath $implementation -Algorithm SHA256).Hash.ToLowerInvariant()
$metadataScanner = Join-Path $PSScriptRoot 'ba2-cv1-expectations.py'
$metadataScannerDigest = (Get-FileHash -LiteralPath $metadataScanner -Algorithm SHA256).Hash.ToLowerInvariant()
$executable = (Get-Command python).Source
$tool = [ordered]@{
    identity = 'jbsa-project-independent-fo4-gnrl-v1-wire-scanner'
    path = $executable
    sha256 = (Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant()
    adapter_version = '1'
    kind = 'archive'
    independent = $true
    derived_from_reference = $false
    implementation = $implementation
    implementation_sha256 = $implementationDigest
    scope = 'ASCII named canonical General BA2 stored/zlib; independent of product and Reference Snapshot source'
}
$expected = @'
{"family":"fo4-gnrl-v1","entries":[{"name":"meshes\\a.nif","size":1024,"payload_sha256":"6ab72eeb9e77b07540897e0c8d6d23ec8eef0f8c3a47e1b3f4e93443d9536bed"},{"name":"meshes\\b.nif","size":4,"payload_sha256":"3d1f57c984978ef98a18378c8166c1cb8ede02c03eeb6aee7e2f121dfeee3e56"}]}
'@ | ConvertFrom-Json -AsHashtable
$result = Invoke-ConformanceValidator -Tool $tool -InputPath $InputPath `
    -InputSha256 (Get-FileHash -LiteralPath $InputPath -Algorithm SHA256).Hash.ToLowerInvariant() `
    -ExpectedProjection $expected -WorkingDirectory $WorkingDirectory -EvidenceDirectory $EvidenceDirectory `
    -Arguments @($implementation, $InputPath)
if ((Get-FileHash -LiteralPath $implementation -Algorithm SHA256).Hash.ToLowerInvariant() -cne $implementationDigest) {
    $result.result = 'INVALID'
    $result.error = 'Independent validator implementation changed during observation'
}
# Cross-provider payload invariants above cannot fix compressed stored sizes. Compare the
# complete public metadata projection with independent parsing of this exact archive instead.
if ($result.result -eq 'PASS') {
    $root = Split-Path $PSScriptRoot -Parent
    $library = @(Get-ChildItem -LiteralPath (Join-Path $root 'jbsa/target') -Filter 'jbsa-*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc)\.jar$' })
    if ($library.Count -ne 1) { throw 'Exactly one packaged library is required for metadata corroboration.' }
    $classes = Join-Path $root 'jbsa-conformance-tests/target/test-classes'
    $observerClass = Join-Path $classes 'io/github/evildarkarchon/jbsa/verification/Ba2PublicObservation.class'
    $java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
    $classpath = @($library[0].FullName, $classes) -join [IO.Path]::PathSeparator
    $metadataExpected = (Get-Content -Raw -LiteralPath $result.observation.stdout.path |
        ConvertFrom-Json -AsHashtable -Depth 100).semantic_projection
    $publicJson = & $java --enable-native-access=ALL-UNNAMED -cp $classpath `
        io.github.evildarkarchon.jbsa.verification.Ba2PublicObservation $InputPath
    if ($LASTEXITCODE -ne 0) { throw 'Public metadata observer failed.' }
    $metadataActual = ($publicJson -join "`n") | ConvertFrom-Json -AsHashtable -Depth 100
    $equal = (ConvertTo-ConformanceCanonicalJson $metadataActual) -ceq (ConvertTo-ConformanceCanonicalJson $metadataExpected)
    $result.metadata_corroboration = @{
        result = $(if ($equal) { 'PASS' } else { 'FAIL' })
        expected = $metadataExpected
        actual = $metadataActual
        expectation_scanner_sha256 = $metadataScannerDigest
        public_observer_sha256 = (Get-FileHash -LiteralPath $observerClass -Algorithm SHA256).Hash.ToLowerInvariant()
        candidate_library_sha256 = (Get-FileHash -LiteralPath $library[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        java_sha256 = (Get-FileHash -LiteralPath $java -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if (-not $equal) { $result.result = 'FAIL'; $result.error = 'Public BA2 metadata differs from independent wire interpretation.' }
}
if ((Get-FileHash -LiteralPath $metadataScanner -Algorithm SHA256).Hash.ToLowerInvariant() -cne $metadataScannerDigest) {
    $result.result = 'INVALID'
    $result.error = 'Independent metadata scanner changed during observation.'
}
[IO.Directory]::CreateDirectory($EvidenceDirectory) | Out-Null
$json = $result | ConvertTo-Json -Depth 100
[IO.File]::WriteAllText((Join-Path $EvidenceDirectory 'observation.json'), $json)
Write-Output $json
if ($result.result -ne 'PASS') { exit 1 }
