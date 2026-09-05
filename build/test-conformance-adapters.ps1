<# .SYNOPSIS
Exercises external-process and pinned-tool boundaries without requiring proprietary tools.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'conformance-adapters.ps1')
Write-Output 'Adapter checks: module loaded.'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) "jbsa-adapters-$([guid]::NewGuid().ToString('N'))"
try {
    $work = New-Item -ItemType Directory -Path (Join-Path $testRoot 'work') -Force
    $evidence = Join-Path $testRoot 'evidence'
    $pwsh = (Get-Command pwsh).Source
    $script = Join-Path $testRoot 'probe.ps1'
    [IO.File]::WriteAllText($script, 'param([string]$Value); [Console]::OpenStandardOutput().Write([byte[]](0,255,10)); [Console]::Error.Write("err"); [IO.File]::WriteAllText("literal.txt", $Value); exit 7')
    Write-Output 'Adapter checks: raw process.'
    $observed = Invoke-ConformanceProcess -Executable $pwsh -Arguments @('-NoProfile', '-File', $script, 'a space; $(not-code)') -WorkingDirectory $work.FullName -EvidenceDirectory $evidence -TimeoutSeconds 10
    if ($observed.result -ne 'PASS' -or $observed.exit_status -ne 7) { throw 'Process invocation did not retain exact exit status.' }
    if ([Convert]::ToHexString([IO.File]::ReadAllBytes($observed.stdout.path)) -cne '00FF0A') { throw 'Raw stdout bytes were transformed.' }
    if ([IO.File]::ReadAllText((Join-Path $work 'literal.txt')) -cne 'a space; $(not-code)') { throw 'Argument list did not preserve literal text.' }
    if (@($observed.filesystem_after | Where-Object path -eq 'literal.txt').Count -ne 1) { throw 'Filesystem observation omitted a created file.' }
    Write-Output 'Adapter checks: timeout.'
    $timeout = Invoke-ConformanceProcess -Executable $pwsh -Arguments @('-NoProfile', '-Command', 'Start-Sleep -Seconds 20') -WorkingDirectory $work.FullName -EvidenceDirectory $evidence -TimeoutSeconds 1
    if ($timeout.result -ne 'INVALID' -or -not $timeout.timed_out) { throw 'Timeout was not reported as invalid evidence.' }
    $childScript = Join-Path $testRoot 'child.ps1'
    [IO.File]::WriteAllText($childScript, 'Start-Sleep -Seconds 60')
    $parentScript = Join-Path $testRoot 'parent.ps1'
    [IO.File]::WriteAllText($parentScript, 'param([string]$Pwsh,[string]$Child); $start = [Diagnostics.ProcessStartInfo]::new($Pwsh); $start.UseShellExecute = $false; $start.CreateNoWindow = $true; $start.ArgumentList.Add("-NoProfile"); $start.ArgumentList.Add("-File"); $start.ArgumentList.Add($Child); $childProcess = [Diagnostics.Process]::Start($start); [IO.File]::WriteAllText("child.pid", [string]$childProcess.Id); exit 0')
    $descendant = Invoke-ConformanceProcess -Executable $pwsh -Arguments @('-NoProfile', '-File', $parentScript, $pwsh, $childScript) -WorkingDirectory $work.FullName -EvidenceDirectory $evidence -TimeoutSeconds 2
    if ($descendant.result -ne 'INVALID' -or -not $descendant.timed_out) { throw 'A descendant retaining redirected pipes did not cause bounded invalidation.' }
    $childProcessId = [int][IO.File]::ReadAllText((Join-Path $work 'child.pid'))
    try {
        $remainingChild = [Diagnostics.Process]::GetProcessById($childProcessId)
        try { if (-not $remainingChild.WaitForExit(5000)) { throw 'Timed-out descendant survived its parent and process job.' } }
        finally { $remainingChild.Dispose() }
    } catch [ArgumentException] {
        # Windows may have already released the terminated descendant's process object.
    }
    Write-Output 'Adapter checks: missing tools and oracle boundaries.'
    $missing = Invoke-ConformanceProcess -Executable (Join-Path $testRoot 'missing.exe') -WorkingDirectory $work.FullName -EvidenceDirectory $evidence
    if ($missing.result -ne 'INVALID') { throw 'A missing process was not invalidated.' }
    if ($env:GITHUB_ACTIONS -ne 'true') {
        $absentOracle = Invoke-ConformanceOracle -RepositoryRoot $testRoot -WorkingDirectory $work.FullName -EvidenceDirectory $evidence
        if ($absentOracle.result -ne 'UNAVAILABLE') { throw 'A missing local oracle must be unavailable.' }
        $oracleDirectory = New-Item -ItemType Directory -Path (Join-Path $testRoot 'tests/fixtures/local/oracle') -Force
        [IO.File]::WriteAllText((Join-Path $oracleDirectory 'BSArch.exe'), 'not the pinned oracle')
        $wrongOracle = Invoke-ConformanceOracle -RepositoryRoot $testRoot -WorkingDirectory $work.FullName -EvidenceDirectory $evidence
        if ($wrongOracle.result -ne 'INVALID' -or $wrongOracle.error -notmatch 'digest') { throw 'A mismatched oracle was not rejected before launch.' }
    } else {
        # Hosted checks exercise the hosted denial; synthetic local provisioning checks belong to local runs.
        $implicitHosted = Invoke-ConformanceOracle -RepositoryRoot 'Z:/must-not-access' -WorkingDirectory $work.FullName -EvidenceDirectory $evidence
        if ($implicitHosted.result -ne 'INVALID' -or $implicitHosted.error -notmatch 'hosted') { throw 'GitHub Actions environment must prohibit implicit local oracle access.' }
    }
    $hostedOracle = Invoke-ConformanceOracle -RepositoryRoot 'Z:/must-not-access' -Hosted -WorkingDirectory $work.FullName -EvidenceDirectory $evidence
    if ($hostedOracle.result -ne 'INVALID' -or $hostedOracle.error -notmatch 'hosted') { throw 'Hosted oracle access was not prohibited before path access.' }
    Write-Output 'Adapter checks: validators.'
    $input = Join-Path $work 'input.bin'
    [IO.File]::WriteAllText($input, 'abc')
    $tool = @{ identity = 'project-authored independent test probe'; path = $pwsh; sha256 = (Get-FileHash $pwsh).Hash; adapter_version = '1'; kind = 'archive'; independent = $true; derived_from_reference = $false }
    $parameters = @{ Tool = $tool; InputPath = $input; InputSha256 = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'; ExpectedProjection = @{ entries = @(@{ name = 'a'; size = 3 }) }; WorkingDirectory = $work.FullName; EvidenceDirectory = $evidence }
    $valid = Invoke-ConformanceValidator @parameters -Arguments @('-NoProfile', '-Command', '[Console]::Write(''{"projection":{"entries":[{"size":3,"name":"a"}]}}'')')
    if ($valid.result -ne 'PASS') { throw "Independent corroboration failed: $($valid.error)" }
    $disagreement = Invoke-ConformanceValidator @parameters -Arguments @('-NoProfile', '-Command', '[Console]::Write(''{"projection":{"entries":[]}}'')')
    if ($disagreement.result -ne 'INVALID') { throw 'Validator disagreement must invalidate the case.' }
    $malformed = Invoke-ConformanceValidator @parameters -Arguments @('-NoProfile', '-Command', '[Console]::Write(''not-json'')')
    if ($malformed.result -ne 'INVALID') { throw 'Malformed validator output must invalidate the case.' }
    $tool.sha256 = '0' * 64
    $unpinned = Invoke-ConformanceValidator @parameters
    if ($unpinned.result -ne 'INVALID') { throw 'Unpinned validator must not execute.' }
    $binding = @{ CaseId = 'CV1-tes3.encode.single.stored.default'; ConfigurationSha256 = '1' * 64; FixtureSha256 = '2' * 64; CandidateSha256 = '3' * 64 }
    $binary = @{ case_id = $binding.CaseId; configuration_sha256 = $binding.ConfigurationSha256; fixture_sha256 = $binding.FixtureSha256; candidate_sha256 = $binding.CandidateSha256; oracle_sha256 = '4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2'; provider_sha256 = '4' * 64; codec_profile_sha256 = '5' * 64; ordered_inputs = $true; switches = @{ split = '0'; share = 'no'; mt = 'no' }; machines = @() }
    foreach ($cpu in @('cpu-one', 'cpu-two')) {
        $runs = @(1..5 | ForEach-Object { @{ fresh = $true; case_id = $binary.case_id; configuration_sha256 = $binary.configuration_sha256; fixture_sha256 = $binary.fixture_sha256; oracle_sha256 = $binary.oracle_sha256; output_sha256 = '6' * 64 } })
        $binary.machines += @{ environment = @{ os = 'windows'; architecture = 'x64'; cpu_id = $cpu; software = 'Pinned OS/tool inventory' }; oracle_runs = $runs; candidate_output_sha256 = '6' * 64; cross_decode = @{ oracle_to_jbsa = 'PASS'; jbsa_to_oracle = 'PASS'; validators = 'PASS' } }
    }
    if ((Test-ConformanceBinaryEvidence -Evidence $binary @binding).result -ne 'PASS') { throw 'Complete case-scoped binary evidence was rejected.' }
    $binary.machines[0].oracle_runs[4].output_sha256 = '7' * 64
    if ((Test-ConformanceBinaryEvidence -Evidence $binary @binding).result -ne 'FAIL') { throw 'Nonrepeatable oracle output must fail binary qualification.' }
    $binary.machines[0].oracle_runs[4].output_sha256 = '6' * 64
    $binary.machines[1].environment.cpu_id = 'cpu-one'
    if ((Test-ConformanceBinaryEvidence -Evidence $binary @binding).result -ne 'INVALID') { throw 'Second CPU confirmation must be independent.' }
    Write-Output 'Conformance adapter regression checks passed.'
}
finally {
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing cleanup outside temp: $resolvedRoot" }
    if (Test-Path -LiteralPath $resolvedRoot) { Remove-Item -LiteralPath $resolvedRoot -Recurse -Force }
}
