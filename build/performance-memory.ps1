param([string]$RequestPath, [string]$ResponsePath)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-JfrHeapHighWater {
    <# .SYNOPSIS Returns the maximum before/after-GC heapUsed value, rejecting absent or malformed evidence. #>
    param([Parameter(Mandatory)][string]$Path)
    $document = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -AsHashtable
    $summaries = @($document.recording.events | Where-Object { $_.type -eq 'jdk.GCHeapSummary' })
    if ($summaries.Count -eq 0) { throw 'JFR contains no GC heap summaries.' }
    [long]$maximum = 0
    foreach ($summary in $summaries) {
        if (-not $summary.values.ContainsKey('heapUsed') -or $summary.values.heapUsed -isnot [ValueType] -or $summary.values.heapUsed -lt 0) {
            throw 'JFR GC heap summary lacks a valid heapUsed counter.'
        }
        $maximum = [Math]::Max($maximum, [long]$summary.values.heapUsed)
    }
    return $maximum
}

function Invoke-MemoryObservation {
    <# .SYNOPSIS Collects one separate Windows Job Object observation; missing instrumentation returns INVALID. #>
    param([Parameter(Mandatory)][hashtable]$Request)
    $result = @{ outcome = 'INVALID'; reasons = @(); heap_high_water_bytes = $null }
    try {
        if (-not $IsWindows -or -not [Environment]::Is64BitProcess) { throw 'Windows x64 memory instrumentation is required.' }
        foreach ($key in @('executable', 'arguments', 'working_directory', 'trace_directory', 'java_process', 'scratch_directory', 'timeout_seconds')) {
            if (-not $Request.ContainsKey($key)) { throw "Missing memory request field: $key" }
        }
        if (-not (Test-Path -LiteralPath $Request.executable -PathType Leaf)) { throw 'The executable must be an existing absolute file path.' }
        if (Test-Path -LiteralPath $Request.trace_directory) { throw 'The memory trace directory must be fresh.' }
        if (-not (Test-Path -LiteralPath $Request.scratch_directory -PathType Container)) { throw 'The scratch directory must exist for measurement.' }
        if ($Request.timeout_seconds -le 0) { throw 'A positive observation timeout is required.' }
        if ($Request.java_process) {
            if (-not $Request.ContainsKey('jfr_path') -or -not $Request.ContainsKey('jfr_tool')) { throw 'Java memory observations require a fresh JFR recording path and pinned jfr tool.' }
            if (Test-Path -LiteralPath $Request.jfr_path) { throw 'The JFR recording path must be fresh.' }
            if (-not (Test-Path -LiteralPath $Request.jfr_tool -PathType Leaf)) { throw 'The pinned jfr tool is missing.' }
        }
        if (-not ('PerformanceMemory' -as [type])) { Add-Type -Path "$PSScriptRoot/performance-memory.cs" }
        $processEnvironment = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::OrdinalIgnoreCase)
        if ($Request.ContainsKey('process_environment')) {
            foreach ($entry in $Request.process_environment.GetEnumerator()) {
                if ($entry.Key -cnotin @('JAVA_TOOL_OPTIONS', 'TMP', 'TEMP') -or $entry.Value -isnot [string] -or $entry.Value.Contains([char]0)) {
                    throw 'Unsupported memory process environment override.'
                }
                $processEnvironment.Add($entry.Key, $entry.Value)
            }
        }
        $observation = [PerformanceMemory]::Run($Request.executable, [string[]]$Request.arguments, $Request.working_directory,
            $Request.trace_directory, $Request.scratch_directory, [int]$Request.timeout_seconds, $processEnvironment)
        $result.exit_code = $observation.ExitCode
        $result.wall_seconds = $observation.WallSeconds
        $result.peak_private_bytes = $observation.PeakPrivateBytes
        $result.peak_working_set_bytes = $observation.PeakWorkingSetBytes
        $result.scratch_peak_bytes = $observation.ScratchPeakBytes
        $result.raw_trace_path = $observation.TracePath
        $result.final_peak_counters = @{
            job_peak_private_bytes = $observation.JobPeakPrivateBytes
            process_peak_private_bytes = $observation.ProcessPeakPrivateBytes
            process_peak_working_set_bytes = $observation.ProcessPeakWorkingSetBytes
            total_processes = $observation.TotalProcesses
            observed_processes = $observation.ObservedProcesses
            processes = $observation.Processes
        }
        if ($observation.ExitCode -ne 0) { throw "Observed process exited with code $($observation.ExitCode)." }
        if ($Request.java_process) {
            if (-not (Test-Path -LiteralPath $Request.jfr_path -PathType Leaf)) { throw 'Java process did not produce its required JFR recording.' }
            $jsonPath = Join-Path $Request.trace_directory 'jfr-heap-summaries.json'
            $start = [Diagnostics.ProcessStartInfo]::new($Request.jfr_tool)
            $start.UseShellExecute = $false
            $start.CreateNoWindow = $true
            $start.RedirectStandardOutput = $true
            $start.RedirectStandardError = $true
            foreach ($argument in @('print', '--json', '--events', 'jdk.GCHeapSummary', $Request.jfr_path)) { $start.ArgumentList.Add($argument) }
            $process = [Diagnostics.Process]::Start($start)
            try {
                $stdout = $process.StandardOutput.ReadToEndAsync()
                $stderr = $process.StandardError.ReadToEndAsync()
                if (-not $process.WaitForExit(60000)) { $process.Kill($true); throw 'JFR extraction timed out.' }
                $stdout.GetAwaiter().GetResult() | Set-Content -LiteralPath $jsonPath -Encoding utf8NoBOM
                $stderr.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $Request.trace_directory 'jfr-stderr.txt') -Encoding utf8NoBOM
                if ($process.ExitCode -ne 0) { throw 'JFR extraction failed.' }
            } finally { $process.Dispose() }
            $result.heap_high_water_bytes = Get-JfrHeapHighWater -Path $jsonPath
            $result.jfr_json_path = $jsonPath
            $result.jfr_path = $Request.jfr_path
        }
        $result.outcome = 'PASS'
    } catch {
        $result.reasons += $_.Exception.Message
    }
    return $result
}

if ($RequestPath -or $ResponsePath) {
    if (-not $RequestPath -or -not $ResponsePath) { throw 'RequestPath and ResponsePath must be supplied together.' }
    $request = Get-Content -LiteralPath $RequestPath -Raw | ConvertFrom-Json -AsHashtable
    Invoke-MemoryObservation -Request $request | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ResponsePath -Encoding utf8NoBOM
}
