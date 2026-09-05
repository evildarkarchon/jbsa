$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. "$PSScriptRoot/performance-memory.ps1"

$scratch = Join-Path "$PSScriptRoot/../target" ('jbsa-memory-test-' + [Guid]::NewGuid())
$scratch = [IO.Path]::GetFullPath($scratch)
[IO.Directory]::CreateDirectory($scratch) | Out-Null
$jsonPath = Join-Path $scratch 'heap.json'
'{"recording":{"events":[{"type":"jdk.GCHeapSummary","values":{"heapUsed":12}},{"type":"jdk.GCHeapSummary","values":{"heapUsed":99}},{"type":"jdk.GCHeapSummary","values":{"heapUsed":31}}]}}' | Set-Content $jsonPath
if ((Get-JfrHeapHighWater -Path $jsonPath) -ne 99) { throw 'Heap must include the maximum summary, including before-GC events.' }
'{"recording":{"events":[]}}' | Set-Content $jsonPath
$rejected = $false
try { Get-JfrHeapHighWater -Path $jsonPath | Out-Null } catch { $rejected = $true }
if (-not $rejected) { throw 'Missing JFR summaries must be invalid.' }

$request = @{
    executable = (Get-Command pwsh).Source
    arguments = @('-NoLogo', '-NoProfile', '-NonInteractive', '-Command', 'Start-Sleep -Milliseconds 180; [Console]::WriteLine("memory-smoke")')
    working_directory = $scratch
    trace_directory = (Join-Path $scratch 'trace')
    java_process = $false
    scratch_directory = $scratch
    timeout_seconds = 20
}
$result = Invoke-MemoryObservation -Request $request
if ($result.outcome -ne 'PASS') { throw ($result | ConvertTo-Json -Depth 10) }
if ($result.peak_private_bytes -le 0 -or $result.peak_working_set_bytes -le 0) { throw 'Final peak counters are required.' }
if (-not (Test-Path $result.raw_trace_path)) { throw 'Raw memory samples were not retained.' }
if ((Get-Content (Join-Path $request.trace_directory 'stdout.txt') -Raw).Trim() -ne 'memory-smoke') { throw 'Child streams must be retained.' }
$request.java_process = $true
$request.trace_directory = Join-Path $scratch 'missing-jfr-trace'
$result = Invoke-MemoryObservation -Request $request
if ($result.outcome -ne 'INVALID') { throw 'Missing Java JFR configuration must invalidate the observation.' }
if (($result.reasons -join ' ') -notmatch 'JFR') { throw 'The missing JFR evidence must be the rejection reason.' }

# These literals cover Win32 argv edge cases independently of the quoting implementation.
if ([PerformanceMemory]::Quote('') -ne '""') { throw 'Empty argv item lost.' }
if ([PerformanceMemory]::Quote('a b\') -ne '"a b\\"') { throw 'Trailing slash must survive quoting.' }
if ([PerformanceMemory]::Quote('a"b') -ne '"a\"b"') { throw 'Embedded quote must survive quoting.' }
$request.java_process = $false
$request.trace_directory = Join-Path $scratch 'timeout-trace'
$request.arguments = @('-NoProfile', '-Command', 'Start-Sleep -Seconds 30')
$request.timeout_seconds = 1
$result = Invoke-MemoryObservation -Request $request
if ($result.outcome -ne 'INVALID' -or ($result.reasons -join ' ') -notmatch 'timed out') { throw 'A timed-out process must be killed and marked invalid.' }

$request.trace_directory = Join-Path $scratch 'descendant-trace'
$request.timeout_seconds = 20
$request.arguments = @('-NoProfile', '-Command', '& pwsh -NoProfile -Command "Start-Sleep -Milliseconds 250"')
$result = Invoke-MemoryObservation -Request $request
if ($result.outcome -ne 'PASS' -or $result.final_peak_counters.total_processes -lt 2) { throw ($result | ConvertTo-Json -Depth 10) }

$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
$jfrPath = Join-Path (Split-Path $javaPath) 'jfr.exe'
if (Test-Path $jfrPath) {
    $sourcePath = Join-Path $scratch 'HeapSmoke.java'
    'class HeapSmoke { public static void main(String[] args) throws Exception { byte[] data = new byte[4000000]; java.nio.file.Files.write(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "scratch.bin"), data); System.gc(); Thread.sleep(150); System.out.println(data.length); } }' | Set-Content $sourcePath
    $request.executable = $javaPath
    $request.java_process = $true
    $request.jfr_tool = $jfrPath
    $request.jfr_path = Join-Path $scratch 'heap.jfr'
    $request.trace_directory = Join-Path $scratch 'java-trace'
    $request.arguments = @($sourcePath)
    $request.process_environment = @{
        TMP = $scratch
        TEMP = $scratch
        JAVA_TOOL_OPTIONS = '"-Djava.io.tmpdir=' + $scratch + '" "-XX:FlightRecorderOptions=repository=' + $scratch + '" "-XX:StartFlightRecording=filename=' + $request.jfr_path + ',dumponexit=true,settings=profile"'
    }
    $result = Invoke-MemoryObservation -Request $request
    if ($result.outcome -ne 'PASS' -or $result.heap_high_water_bytes -le 0) { throw ($result | ConvertTo-Json -Depth 10) }
    if ($result.scratch_peak_bytes -lt 4000000 -or -not (Test-Path (Join-Path $scratch 'scratch.bin'))) { throw 'The observed process must write to its measured scratch directory.' }
} else {
    Write-Host 'Java recording integration smoke skipped: set JAVA_HOME to a JDK with jfr.exe.'
}
Write-Host 'Performance memory tests passed.'
