<# .SYNOPSIS Observes a proposed CV1 case exclusively through the packaged library or public CLI. #>
param([Parameter(Mandatory)][string]$Request)
$ErrorActionPreference = 'Stop'
$requestData = Get-Content -Raw -LiteralPath $Request | ConvertFrom-Json -AsHashtable -Depth 100
$root = $requestData.repository_root
. (Join-Path $PSScriptRoot 'bsa-cv1-review-evidence.ps1')
$case = $requestData.case
$familySelector = if ($case.identity.archive_family -eq 'bsa-068') { '-fo3' } else { '-tes4' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$library = @($requestData.candidate_artifacts | Where-Object { $_.path -like 'jbsa/target/*' })[0]
$cli = @($requestData.candidate_artifacts | Where-Object { $_.path -like 'jbsa-cli/target/*' })[0]
$classpath = @((Join-Path $root $library.path), (Join-Path $root $cli.path),
    (Join-Path $root 'jbsa-conformance-tests/target/test-classes')) -join [IO.Path]::PathSeparator
$fixture = Join-Path $root $case.metadata.fixture_binding.files[0].path
$fixtureDigest = (Get-FileHash -LiteralPath $fixture -Algorithm SHA256).Hash.ToLowerInvariant()
$phase = $requestData.phase
$scenario = if ($case.identity.fixture -match '^bsa-068-scenario-(.+)-v1$') { $Matches[1] } else { $null }
if ($phase -eq 'oracle-to-jbsa') {
    $inputArchive = $requestData.input_archive.path
    $operation = 'oracle-to-jbsa'
} else {
    $inputArchive = Join-Path (Get-Location) $(if ($fixture.EndsWith('.json')) { 'input.json' } else { 'input.bsa' })
    if ($fixture.EndsWith('.json')) { [IO.File]::Copy($fixture, $inputArchive) }
    else { [IO.File]::WriteAllBytes($inputArchive, [Convert]::FromHexString([IO.File]::ReadAllText($fixture).Trim())) }
    $operation = $case.identity.operation
    if ($operation -eq 'scenario' -and -not $scenario) { $operation = 'encode' }
}
$filesystemBefore = @(Get-ConformanceFilesystem -Root (Get-Location).Path)
if ($scenario) {
    $class = if ($scenario -in @('bsa-automatic-flags', 'bsa-dds-empty-stem', 'bsa-embedded-068', 'bsa-hash-collisions', 'bsa-name-flags')) {
        'Bsa68FramingScenarios'
    } elseif ($scenario -in @('compression-consumption', 'compression-mixed', 'compression-size-mismatch', 'source-later-part-collision', 'source-split-names')) {
        'Bsa68FormatScenarios'
    } else { 'Bsa68ScenarioObservation' }
    $text = & $java --enable-native-access=ALL-UNNAMED -cp $classpath `
        "io.github.evildarkarchon.jbsa.verification.$class" $scenario $inputArchive
    if ($LASTEXITCODE -ne 0) { throw 'Public scenario observation process failed' }
    $observed = ($text -join "`n") | ConvertFrom-Json -AsHashtable -Depth 100
} elseif ($operation -eq 'encode' -and $case.identity.codec -eq 'raw-deflate') {
    $stream = & $java --enable-native-access=ALL-UNNAMED -cp $classpath `
        io.github.evildarkarchon.jbsa.cli.Main pack $inputArchive candidate.bsa $familySelector -z:raw-deflate 2>&1
    $observed = @{ exit_status = $LASTEXITCODE; artifact_exists = (Test-Path -LiteralPath 'candidate.bsa') }
} else {
    $text = & $java --enable-native-access=ALL-UNNAMED -cp $classpath `
        io.github.evildarkarchon.jbsa.verification.BsaPublicObservation $inputArchive $operation $case.identity.codec $case.identity.archive_family
    if ($LASTEXITCODE -ne 0) { throw 'Public observation process failed' }
    $observed = ($text -join "`n") | ConvertFrom-Json -AsHashtable -Depth 100
}
if ($operation -in @('encode', 'extract') -and ($observed.Contains('failure_kind') -or $observed.Contains('exit_status'))) {
    # The traversal fixture escapes the extraction destination into this working tree; the complete
    # snapshot also detects stray files, empty directories, and modifications to the original input.
    $observed = Add-BsaRejectedFilesystemEvidence -Observation $observed -Before $filesystemBefore -WorkingDirectory (Get-Location).Path
}
if ($phase -eq 'oracle-to-jbsa') {
    @{ case_id = $case.identity.case_id; assertions = @(@{ assertion_id = 'oracle-to-jbsa'; observed = $observed }) } |
        ConvertTo-Json -Depth 100 -Compress
    exit 0
}
$assertions = @(foreach ($id in $case.metadata.assertions) {
    if ($case.identity.operation -eq 'encode' -and $case.metadata.expected_behavior -eq 'accept' -and $id -eq 'oracle-to-jbsa') { continue }
    $value = if ($id -eq 'fixture-integrity') { $fixtureDigest } else { $observed }
    @{ assertion_id = $id; observed = $value }
})
$archive = if (Test-Path -LiteralPath 'candidate.bsa') { 'candidate.bsa' } else { [IO.Path]::GetFileName($inputArchive) }
$artifactRole = if ($archive.EndsWith('.json')) { 'scenario-recipe' } else { 'archive' }
@{ case_id = $case.identity.case_id; assertions = $assertions
    artifacts = @(@{ role = $artifactRole; path = $archive; sha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() }) } |
    ConvertTo-Json -Depth 100 -Compress
