<# .SYNOPSIS
Bounded process, local oracle, and independent validator adapters for conformance-v1.
#>
. (Join-Path $PSScriptRoot 'conformance-evidence.ps1')

function New-ConformanceProcessJob {
    <# .SYNOPSIS Creates a Windows job whose lifetime owns every descendant of the observing process. #>
    if (-not ('Jbsa.Conformance.ProcessJob' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
namespace Jbsa.Conformance {
    /// <summary>Owns a Windows process tree independently of the original parent's lifetime.</summary>
    public sealed class ProcessJob : IDisposable {
        private readonly SafeFileHandle handle;
        [StructLayout(LayoutKind.Sequential)] private struct BasicLimits {
            public long PerProcessTime, PerJobTime;
            public uint Flags;
            public UIntPtr MinimumWorkingSet, MaximumWorkingSet;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass, SchedulingClass;
        }
        [StructLayout(LayoutKind.Sequential)] private struct IoCounters {
            public ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes;
        }
        [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits {
            public BasicLimits Basic;
            public IoCounters Io;
            public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
        }
        [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] private static extern SafeFileHandle CreateJobObject(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError=true)] private static extern bool SetInformationJobObject(SafeFileHandle job, int infoClass, ref ExtendedLimits limits, uint length);
        [DllImport("kernel32.dll", SetLastError=true)] private static extern bool AssignProcessToJobObject(SafeFileHandle job, IntPtr process);
        [DllImport("kernel32.dll", SetLastError=true)] private static extern bool TerminateJobObject(SafeFileHandle job, uint exitCode);
        /// <summary>Creates a non-inheritable job with kill-on-close ownership.</summary>
        public ProcessJob() {
            handle = CreateJobObject(IntPtr.Zero, null);
            if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            var limits = new ExtendedLimits();
            limits.Basic.Flags = 0x2000;
            if (!SetInformationJobObject(handle, 9, ref limits, (uint)Marshal.SizeOf<ExtendedLimits>())) {
                int error = Marshal.GetLastWin32Error(); handle.Dispose(); throw new Win32Exception(error);
            }
        }
        /// <summary>Attaches a newly started process before the harness begins observing it.</summary>
        public void Assign(Process process) {
            if (!AssignProcessToJobObject(handle, process.Handle)) throw new Win32Exception(Marshal.GetLastWin32Error());
        }
        /// <summary>Terminates all members, including descendants whose original parent has exited.</summary>
        public void Dispose() {
            if (!handle.IsClosed) { TerminateJobObject(handle, 1); handle.Dispose(); }
        }
    }
}
'@
    }
    return [Jbsa.Conformance.ProcessJob]::new()
}

function Get-ConformanceFileRecord {
    <# .SYNOPSIS Returns the exact file-byte identity retained by an observation. #>
    param([Parameter(Mandatory)][string]$Path)
    return [ordered]@{ path = [IO.Path]::GetFullPath($Path); sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant(); size = (Get-Item -LiteralPath $Path).Length }
}

function Get-ConformanceFilesystem {
    <# .SYNOPSIS Records ordered relative paths and file bytes without following filesystem indirections. #>
    param([Parameter(Mandatory)][string]$Root)
    $rootPath = [IO.Path]::GetFullPath($Root)
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($rootPath)
    $records = [Collections.Generic.List[object]]::new()
    while ($pending.Count -gt 0) {
        $directory = $pending.Pop()
        if ((Get-Item -LiteralPath $directory).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Filesystem observation cannot follow an indirection.' }
        foreach ($item in Get-ChildItem -LiteralPath $directory -Force) {
            $relative = [IO.Path]::GetRelativePath($rootPath, $item.FullName).Replace('\', '/')
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                $records.Add([ordered]@{ path = $relative; kind = 'indirection' })
            } elseif ($item.PSIsContainer) {
                $records.Add([ordered]@{ path = $relative; kind = 'directory' })
                $pending.Push($item.FullName)
            } else {
                $records.Add([ordered]@{ path = $relative; kind = 'file'; size = $item.Length; sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant() })
            }
        }
    }
    $ordered = [Collections.Generic.SortedDictionary[string,object]]::new([StringComparer]::Ordinal)
    foreach ($record in $records) { $ordered.Add($record.path, $record) }
    return @($ordered.Values)
}

function Invoke-ConformanceProcess {
    <# .SYNOPSIS
    Runs a process with literal argument boundaries, finite lifetime, raw streams, and filesystem observations.
    .DESCRIPTION
    PASS means observation succeeded, including a nonzero application exit. Timeouts, excessive output,
    launch failures, and incomplete stream capture are INVALID. EvidenceDirectory must be outside the observed tree.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Executable,
        [AllowEmptyCollection()][string[]]$Arguments = @(),
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [ValidateRange(1,3600)][int]$TimeoutSeconds = 30,
        [ValidateRange(1024,1073741824)][long]$MaximumStreamBytes = 16777216
    )
    $observation = [ordered]@{ result = 'INVALID'; exit_status = $null; timed_out = $false; stdout = $null; stderr = $null; filesystem_before = @(); filesystem_after = @(); filesystem_evidence = @(); artifact_evidence = @(); invocation = @($Executable) + $Arguments; error = $null }
    $process = [Diagnostics.Process]::new()
    $job = $null
    $started = $false
    $streams = @()
    $rawPaths = @()
    try {
        $work = [IO.Path]::GetFullPath($WorkingDirectory)
        $evidence = [IO.Path]::GetFullPath($EvidenceDirectory)
        if ($evidence.Equals($work, [StringComparison]::OrdinalIgnoreCase) -or $evidence.StartsWith($work.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Evidence directory must be outside the observed working tree.' }
        [IO.Directory]::CreateDirectory($evidence) | Out-Null
        $observation.filesystem_before = @(Get-ConformanceFilesystem -Root $work)
        $process.StartInfo.FileName = $Executable
        $process.StartInfo.WorkingDirectory = $work
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        $process.StartInfo.RedirectStandardInput = $true
        foreach ($argument in $Arguments) { $process.StartInfo.ArgumentList.Add($argument) }
        foreach ($name in @('stdout', 'stderr')) {
            $rawPath = Join-Path $evidence "$([guid]::NewGuid().ToString('N')).$name.raw"
            $rawPaths += $rawPath
            $streams += [IO.File]::Open($rawPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
        }
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $job = New-ConformanceProcessJob
        if (-not $process.Start()) { throw 'Process did not start.' }
        $started = $true
        $job.Assign($process)
        # These are noninteractive observations; an inherited open Java stdin pipe can stall PowerShell startup.
        $process.StandardInput.Close()
        # Drain both byte streams concurrently: text readers change binary evidence and sequential reads can deadlock.
        $copies = @($process.StandardOutput.BaseStream.CopyToAsync($streams[0]), $process.StandardError.BaseStream.CopyToAsync($streams[1]))
        while (-not $process.WaitForExit(25)) {
            if ($clock.Elapsed.TotalSeconds -ge $TimeoutSeconds) { $observation.timed_out = $true; throw 'Process exceeded its timeout.' }
            if ($streams[0].Length -gt $MaximumStreamBytes -or $streams[1].Length -gt $MaximumStreamBytes) { throw 'Process exceeded its stream byte limit.' }
        }
        # A descendant can retain a pipe after its parent exits, so stream completion is bounded too.
        $remaining = [Math]::Max(1, [int](1000 * $TimeoutSeconds - $clock.Elapsed.TotalMilliseconds))
        if (-not [Threading.Tasks.Task]::WhenAll([Threading.Tasks.Task[]]$copies).Wait($remaining)) { $observation.timed_out = $true; throw 'Stream capture exceeded its timeout.' }
        if ($streams[0].Length -gt $MaximumStreamBytes -or $streams[1].Length -gt $MaximumStreamBytes) { throw 'Process exceeded its stream byte limit.' }
        $observation.exit_status = $process.ExitCode
        $observation.result = 'PASS'
    } catch {
        $observation.error = $_.Exception.Message
    } finally {
        # The job outlives the initial process and closes inherited pipes even when that process has already exited.
        if ($null -ne $job) { $job.Dispose() }
        try {
            if ($started -and -not $process.HasExited) { $process.Kill($true); [void]$process.WaitForExit(5000) }
        } catch {
            # Exit can race with termination; any unconfirmed cleanup invalidates this observation.
            $observation.result = 'INVALID'
            $observation.error = "Process cleanup was not confirmed: $($_.Exception.Message)"
        }
        foreach ($stream in $streams) { $stream.Dispose() }
        $process.Dispose()
    }
    try {
        foreach ($index in 0..1) {
            if ($index -lt $rawPaths.Count) {
                $record = Get-ConformanceFileRecord -Path $rawPaths[$index]
                $stored = Add-ConformanceEvidence -Directory $evidence -Bytes ([IO.File]::ReadAllBytes($rawPaths[$index]))
                [IO.File]::Delete($rawPaths[$index])
                $record.path = $stored.path
                $observation[@('stdout', 'stderr')[$index]] = $record
            }
        }
        $observation.filesystem_after = @(Get-ConformanceFilesystem -Root $WorkingDirectory)
        foreach ($tree in @('filesystem_before', 'filesystem_after')) {
            $observation.filesystem_evidence += Add-ConformanceEvidence -Directory $evidence -Bytes ([Text.Encoding]::UTF8.GetBytes((ConvertTo-ConformanceCanonicalJson $observation[$tree])))
        }
        foreach ($file in $observation.filesystem_after | Where-Object kind -eq 'file') {
            $path = Join-Path $WorkingDirectory $file.path
            $retained = Add-ConformanceEvidence -Directory $evidence -Bytes ([IO.File]::ReadAllBytes($path))
            if ($retained.sha256 -cne $file.sha256) { throw 'Observed filesystem bytes changed during evidence retention.' }
            $observation.artifact_evidence += $retained
        }
    } catch { $observation.result = 'INVALID'; $observation.error = $_.Exception.Message }
    return $observation
}

function Test-ConformanceOracleIdentity {
    <# .SYNOPSIS Checks local oracle eligibility without execution so missing tools can prevent a case from starting. #>
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepositoryRoot, [switch]$Hosted)
    $identity = [ordered]@{ product = 'BSArch v1.0 x64'; sha256 = '4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2'; source_revision = 'fd1e36020b2b5b6217e553dc0038983146a2e2dd'; source_correspondence = 'user-attested; unavailable Delphi build prevents cryptographic reproduction'; path = 'tests/fixtures/local/oracle/BSArch.exe' }
    if ($Hosted -or $env:GITHUB_ACTIONS -eq 'true') { return [ordered]@{ result = 'INVALID'; oracle = $identity; error = 'Conformance Oracle access is prohibited in hosted execution.' } }
    $oraclePath = Join-Path $RepositoryRoot $identity.path
    try {
        if (-not (Test-Path -LiteralPath $oraclePath -PathType Leaf)) { return [ordered]@{ result = 'UNAVAILABLE'; oracle = $identity; error = 'Local Conformance Oracle is absent.' } }
        $actual = (Get-FileHash -LiteralPath $oraclePath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actual -cne $identity.sha256) { return [ordered]@{ result = 'INVALID'; oracle = $identity; observed_sha256 = $actual; error = 'Conformance Oracle digest mismatch; executable was not run.' } }
        return [ordered]@{ result = 'PASS'; oracle = $identity; error = $null }
    } catch { return [ordered]@{ result = 'INVALID'; oracle = $identity; error = $_.Exception.Message } }
}

function Invoke-ConformanceOracle {
    <# .SYNOPSIS
    Verifies the canonical BSArch digest immediately before each invocation; hosted execution is prohibited.
    .DESCRIPTION
    A missing local executable is UNAVAILABLE. Mismatch or hosted invocation is INVALID without execution.
    The source correspondence remains user-attested, not cryptographically reproducible.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepositoryRoot,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [ValidateRange(1,3600)][int]$TimeoutSeconds = 30,
        [switch]$Hosted
    )
    $readiness = Test-ConformanceOracleIdentity -RepositoryRoot $RepositoryRoot -Hosted:$Hosted
    if ($readiness.result -cne 'PASS') { return $readiness }
    $identity = $readiness.oracle
    $oraclePath = Join-Path $RepositoryRoot $identity.path
    $oracleHandle = $null
    try {
        # Keep a read-only sharing lock from verification through exit so Windows cannot replace the checked executable.
        $oracleHandle = [IO.File]::Open($oraclePath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        $actual = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($oracleHandle)).ToLowerInvariant()
        if ($actual -cne $identity.sha256) { return [ordered]@{ result = 'INVALID'; oracle = $identity; observed_sha256 = $actual; error = 'Conformance Oracle digest mismatch; executable was not run.' } }
        # No readiness cache: every operation, including each repetition, verifies the bytes it will execute.
        $observation = Invoke-ConformanceProcess -Executable $oraclePath -Arguments $Arguments -WorkingDirectory $WorkingDirectory -EvidenceDirectory $EvidenceDirectory -TimeoutSeconds $TimeoutSeconds
        $observation.oracle = $identity
        return $observation
    } catch { return [ordered]@{ result = 'INVALID'; oracle = $identity; error = $_.Exception.Message } }
    finally { if ($null -ne $oracleHandle) { $oracleHandle.Dispose() } }
}

function Invoke-ConformanceValidator {
    <# .SYNOPSIS
    Corroborates an authority projection with a digest-pinned independent archive or DirectXTex DDS tool.
    .DESCRIPTION
    Tool records bind identity, path, sha256, adapter_version, kind, independent, and derived_from_reference.
    The registered invocation must emit one UTF-8 JSON object containing projection. Accepted input bytes are
    checked before launch. Disagreement and malformed evidence are INVALID; validators never set expectations.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Tool,
        [Parameter(Mandatory)][string]$InputPath,
        [Parameter(Mandatory)][string]$InputSha256,
        [Parameter(Mandatory)]$ExpectedProjection,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][string]$EvidenceDirectory,
        [ValidateRange(1,3600)][int]$TimeoutSeconds = 30
    )
    $record = [ordered]@{ result = 'INVALID'; validator = $Tool; invocation = @($Tool.path) + $Arguments; input = $null; output = $null; normalized_result = $null; observation = $null; expected = $ExpectedProjection; error = $null }
    try {
        foreach ($field in @('identity', 'path', 'sha256', 'adapter_version', 'kind', 'independent', 'derived_from_reference')) { if (-not $Tool.Contains($field)) { throw "Missing validator identity field: $field" } }
        if ($Tool.independent -isnot [bool] -or -not $Tool.independent -or $Tool.derived_from_reference -isnot [bool] -or $Tool.derived_from_reference) { throw 'Validator must attest independence from xEdit and the Reference Snapshot.' }
        if ([string]::IsNullOrWhiteSpace($Tool.identity) -or [string]::IsNullOrWhiteSpace($Tool.adapter_version) -or $Tool.identity -match '(?i)(xedit|bsarch|tes5edit)' -or $Tool.kind -notin @('archive', 'dds')) { throw 'Validator identity or adapter version is not eligible.' }
        if ($Tool.kind -eq 'dds' -and $Tool.identity -notmatch '(?i)directxtex') { throw 'Canonical DDS validation requires DirectXTex.' }
        if ($Tool.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or $InputSha256 -notmatch '^[a-fA-F0-9]{64}$') { throw 'Tool and accepted input digests must be SHA-256.' }
        if (-not (Test-Path -LiteralPath $Tool.path -PathType Leaf)) { $record.result = 'UNAVAILABLE'; throw 'Independent validator is not provisioned.' }
        if ((Get-FileHash -LiteralPath $Tool.path -Algorithm SHA256).Hash -ine $Tool.sha256) { throw 'Independent validator digest mismatch; executable was not run.' }
        $record.input = Get-ConformanceFileRecord -Path $InputPath
        if ($record.input.sha256 -ine $InputSha256) { throw 'Accepted validator input digest mismatch.' }
        $record.observation = Invoke-ConformanceProcess -Executable $Tool.path -Arguments $Arguments -WorkingDirectory $WorkingDirectory -EvidenceDirectory $EvidenceDirectory -TimeoutSeconds $TimeoutSeconds
        if ($record.observation.result -ne 'PASS' -or $record.observation.exit_status -ne 0) { throw 'Independent validator did not produce a successful observation.' }
        $record.output = $record.observation.stdout
        $utf8 = [Text.UTF8Encoding]::new($false, $true)
        $parsed = $utf8.GetString([IO.File]::ReadAllBytes($record.output.path)) | ConvertFrom-Json -AsHashtable -Depth 100
        if ($parsed -isnot [Collections.IDictionary] -or -not $parsed.Contains('projection') -or $null -eq $parsed.projection) { throw 'Validator JSON must contain a semantic projection.' }
        $record.normalized_result = $parsed.projection
        if ((ConvertTo-ConformanceCanonicalJson $ExpectedProjection) -cne (ConvertTo-ConformanceCanonicalJson $parsed.projection)) { throw 'Independent Validator disagreement with the applicable authority projection.' }
        # A tool that mutates its accepted input has not corroborated those pinned bytes.
        if ((Get-FileHash -LiteralPath $InputPath -Algorithm SHA256).Hash -ine $InputSha256) { throw 'Validator changed its accepted input.' }
        $record.result = 'PASS'
    } catch { $record.error = $_.Exception.Message }
    return $record
}

function Test-ConformanceBinaryEvidence {
    <# .SYNOPSIS
    Validates five fresh oracle repetitions and cross-decodes on each of two Windows x64 CPUs for one exact case.
    .DESCRIPTION
    This validates separately collected manual evidence; it never runs qualification or broadens the claim.
    Missing identities or prerequisites are INVALID, while observed byte inequality is FAIL.
    #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][Collections.IDictionary]$Evidence,
        [Parameter(Mandatory)][string]$CaseId,
        [Parameter(Mandatory)][string]$ConfigurationSha256,
        [Parameter(Mandatory)][string]$FixtureSha256,
        [Parameter(Mandatory)][string]$CandidateSha256
    )
    $result = [ordered]@{ result = 'INVALID'; scope = 'case-and-exact-configuration'; case_id = $CaseId; configuration_sha256 = $ConfigurationSha256; evidence = $Evidence; error = $null }
    try {
        $expected = @{ case_id = $CaseId; configuration_sha256 = $ConfigurationSha256; fixture_sha256 = $FixtureSha256; candidate_sha256 = $CandidateSha256; oracle_sha256 = '4c34fe4173a2bd04ba52d5a6357348256ee424573785085fdafaab524cf7b0c2' }
        foreach ($key in $expected.Keys) { if (-not $Evidence.Contains($key) -or $Evidence[$key] -cne $expected[$key]) { throw "Binary qualification binding mismatch: $key" } }
        foreach ($key in @('configuration_sha256', 'fixture_sha256', 'candidate_sha256', 'oracle_sha256', 'provider_sha256', 'codec_profile_sha256')) { if ($Evidence[$key] -cnotmatch '^[a-f0-9]{64}$') { throw "Missing binary qualification digest: $key" } }
        if ($Evidence.ordered_inputs -isnot [bool] -or -not $Evidence.ordered_inputs -or $Evidence.switches.split -cne '0' -or $Evidence.switches.share -cne 'no' -or $Evidence.switches.mt -cne 'no') { throw 'Binary qualification requires ordered inputs, -split:0, -share:no, and -mt:no.' }
        if (@($Evidence.machines).Count -lt 2) { throw 'Binary qualification requires a second Windows x64 CPU.' }
        $cpuIdentities = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $digests = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($machine in $Evidence.machines) {
            $environment = $machine.environment
            if ($environment.os -cne 'windows' -or $environment.architecture -cne 'x64' -or [string]::IsNullOrWhiteSpace($environment.cpu_id) -or -not $environment.software) { throw 'Binary qualification requires complete Windows x64 machine and software identities.' }
            if (-not $cpuIdentities.Add($environment.cpu_id)) { throw 'Binary qualification requires distinct CPU identities.' }
            if (@($machine.oracle_runs).Count -lt 5) { throw 'Binary qualification requires five fresh oracle runs per CPU.' }
            foreach ($run in $machine.oracle_runs) {
                if ($run.fresh -isnot [bool] -or -not $run.fresh) { throw 'Oracle repetition did not use a fresh execution.' }
                foreach ($key in @('case_id', 'configuration_sha256', 'fixture_sha256', 'oracle_sha256')) { if ($run[$key] -cne $expected[$key]) { throw "Oracle repetition binding mismatch: $key" } }
                if ($run.output_sha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Oracle repetition lacks exact output identity.' }
                [void]$digests.Add($run.output_sha256)
            }
            if ($machine.candidate_output_sha256 -cnotmatch '^[a-f0-9]{64}$') { throw 'Candidate output lacks exact identity.' }
            [void]$digests.Add($machine.candidate_output_sha256)
            foreach ($direction in @('oracle_to_jbsa', 'jbsa_to_oracle', 'validators')) { if ($machine.cross_decode[$direction] -cne 'PASS') { throw "Binary qualification requires successful $direction evidence." } }
        }
        if ($digests.Count -ne 1) { $result.result = 'FAIL'; throw 'Oracle repetitions and candidate outputs are not byte-identical.' }
        $result.result = 'PASS'
    } catch { $result.error = $_.Exception.Message }
    return $result
}
