<# .SYNOPSIS Rejects stale packaged profiles and buggy rejection paths that leave filesystem effects. #>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'bsa-cv1-review-evidence.ps1')
$root = Split-Path $PSScriptRoot -Parent
$temporary = Join-Path $root ('target/bsa-review-evidence-test-' + [Guid]::NewGuid().ToString('N'))
$work = Join-Path $temporary 'work'
[IO.Directory]::CreateDirectory($work) | Out-Null
[IO.File]::WriteAllText((Join-Path $work 'input.bsa'), 'independent fixture bytes')
$before = @(Get-ConformanceFilesystem -Root $work)
$expected = Add-BsaRejectedFilesystemEvidence -Observation @{ failure_kind = 'UNSUPPORTED' } -Before $before -WorkingDirectory $work
$unchanged = Add-BsaRejectedFilesystemEvidence -Observation @{ failure_kind = 'UNSUPPORTED' } -Before $before -WorkingDirectory $work
if ((Compare-ConformanceValue $expected $unchanged exact).result -ne 'PASS') { throw 'An effect-free rejection must match.' }
# Simulate a buggy adapter returning the correct failure while leaving a destination artifact.
[IO.File]::WriteAllText((Join-Path $work 'candidate.bsa'), 'unexpected output')
$leaking = Add-BsaRejectedFilesystemEvidence -Observation @{ failure_kind = 'UNSUPPORTED' } -Before $before -WorkingDirectory $work
if ((Compare-ConformanceValue $expected $leaking exact).result -ne 'FAIL') { throw 'Correct failure plus output effects must fail.' }
Remove-Item -LiteralPath (Join-Path $work 'candidate.bsa')
# The traversal fixture's escaped path leaves extracted/ and lands at work/abc.
[IO.Directory]::CreateDirectory((Join-Path $work 'abc')) | Out-Null
[IO.File]::WriteAllText((Join-Path $work 'abc/a.nif'), 'escaped output')
$escaped = Add-BsaRejectedFilesystemEvidence -Observation @{ failure_kind = 'UNSUPPORTED' } -Before $before -WorkingDirectory $work
if ((Compare-ConformanceValue $expected $escaped exact).result -ne 'FAIL') { throw 'Escaped extraction output must fail.' }
$source = Join-Path $temporary 'profile.json'
[IO.File]::WriteAllText($source, '{"profile_id":"test-only"}')
$digest = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$jar = Join-Path $temporary 'candidate.jar'
$zip = [IO.Compression.ZipFile]::Open($jar, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in @(@('META-INF/jbsa-codec-profile.json', [IO.File]::ReadAllText($source)),
                         @('META-INF/jbsa-codec-profile.sha256', $digest))) {
        $writer = [IO.StreamWriter]::new($zip.CreateEntry($entry[0]).Open(), [Text.UTF8Encoding]::new($false))
        try { $writer.Write($entry[1]) } finally { $writer.Dispose() }
    }
} finally { $zip.Dispose() }
if ((Get-BsaPackagedProfile -Jar $jar -SourceProfile $source).sha256 -cne $digest) { throw 'Matching packaged profile rejected.' }
[IO.File]::WriteAllText($source, '{"profile_id":"new-source-old-jar"}')
$rejected = $false
try { $null = Get-BsaPackagedProfile -Jar $jar -SourceProfile $source }
catch { $rejected = $_.Exception.Message -like '*differs from its sidecar or source*' }
if (-not $rejected) { throw 'An old packaged profile must not qualify under a new source digest.' }
Write-Host 'BSA rejection filesystem and packaged-profile regression checks passed.'
