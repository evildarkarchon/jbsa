# Local performance instrumentation

This build-only module packages JMH 1.37 as
`target/jbsa-benchmarks-<version>-standalone.jar`. Ordinary Maven verification
compiles and tests the harness; it does not run performance qualification.

The `RandomAccessBenchmark.metadata` and `.payload` benchmarks use three forks,
five two-second warmup iterations, and ten two-second measurement iterations.
Fork JVM options are exactly `-Xms4g -Xmx4g -XX:+AlwaysPreTouch -XX:+UseG1GC`.
The qualification runner must verify the actual JVM, options, provider,
distribution, corpus and protocol identities, and retain JMH JSON (`-rf json
-rff <fresh-result.json>`) plus stdout/stderr with every fork and iteration.
Run `-bm sample` and `-bm thrpt` separately, with no profiler for timing.
Allocation profiling, if requested, is a separate invocation and cannot supply
timing acceptance samples.

Supply all four JMH parameters with `-p name=value`:

| Parameter | Bound value |
| --- | --- |
| `archivePath` | Absolute path to the pre-generated, digest-verified archive |
| `manifestPath` | Absolute path to its digest-verified entry manifest |
| `providerIdentity` | Exact immutable codec/provider/configuration identity |
| `seed` | Manifest-pinned signed 64-bit entry-selection seed |

`ArchiveAccessProvider` is a service-provider seam for the future production
public random-access API. An implementation must be registered under
`META-INF/services/io.github.evildarkarchon.jbsa.benchmarks.ArchiveAccessProvider`,
return the requested identity, validate the manifest in trial setup, and delegate
metadata lookup and payload reads to that API. **No production implementation is
available yet.** Unconfigured or missing providers fail setup, so this artifact
cannot claim current archive performance. Tests exercise stream completion and
the missing-provider boundary without shipping a fake adapter.

The bound corpus manifest is JSON with a `files` array. Each file supplies its
relative `path`, exact `length`, and `sha256`; the adapter maps these entries to
`Entry(key, uncompressedBytes)` without trusting archive-reported sizes. The
runner validates the manifest digest and provider token's full identity mapping
before it starts JMH.

Payload cases contain exactly one fixed-size class. The adapter's entries carry
manifest-declared uncompressed lengths, and setup rejects mixed-size payload
classes. The runner converts each throughput sample independently:
`logical MiB/s = read operations/s * declared bytes/read / 1048576`.
Metadata cases may contain varying sizes. Every payload operation must reach
normal EOF, return the exact declared length, and close its stream; truncation,
extra bytes, and read failures abort the benchmark. Filesystem cache warming and
archive generation belong to the outer runner before invocation.

## Separate Windows memory observations

`build/performance-memory.ps1 -RequestPath <request.json> -ResponsePath
<response.json>` performs one memory observation. The runner supplies five fresh
requests for each required executable and applies the maximum/median and paired
ceilings. It must never use these instrumented wall times as throughput samples.

The request object requires `executable`, `arguments` (an argv array),
`working_directory`, `trace_directory` (must not exist), `java_process`,
`scratch_directory` (must exist), and positive `timeout_seconds`. A Java request
also requires fresh `jfr_path` and pinned `jfr_tool`. Its launcher arguments must
enable a recording with `jdk.GCHeapSummary` events and dump-on-exit, or supply
those options through the recorded `process_environment.JAVA_TOOL_OPTIONS`.
The qualification runner keeps the shipping executable and semantic argv
unchanged, routes `TMP`/`TEMP` and Java temporary files to measured scratch, and
adds only the separate JFR recording through `JAVA_TOOL_OPTIONS`. Its recording
repository sits outside measured scratch. The process environment override API
accepts only `JAVA_TOOL_OPTIONS`, `TMP`, and `TEMP`, and does not alter the harness
environment. Native executables set `java_process` to false.
The executable must be directly launchable by Win32 CreateProcess; a `.cmd`
launcher requires its explicit command interpreter invocation.

The child starts suspended, is assigned to a kill-on-close Windows Job Object,
then resumes. A 50 ms schedule samples summed private committed bytes and working
set for the process tree, and separately samples the scratch directory's total
file bytes. Keep that directory distinct from logs and produced output. The
trace retains actual monotonic timestamps, allowing scheduling jitter to be
examined. The harness retains handles for every observed descendant, reads each
member's final Win32 peaks and the final Job Object private-memory peak, and
rejects missed descendants or missing counters. The reported process-tree peaks
are the maximum of sampled totals and summed member peaks (a conservative upper
bound for non-coincident child spikes). Each member's final counters are retained
so this aggregation remains auditable.

The response contains `outcome`, `reasons`, `exit_code`, `wall_seconds`,
`peak_private_bytes`, `peak_working_set_bytes`, `heap_high_water_bytes`,
`scratch_peak_bytes`, `raw_trace_path`, and `final_peak_counters`. Java responses
also retain `jfr_path` and `jfr_json_path`; heap high-water is the maximum heapUsed
over all GC heap summaries, including before-GC events. Native heap is not
invented by subtracting independently observed Java and process peaks. Native
processes retain their actual process-tree memory counters with a null Java heap.
Missing JFR, missing final peaks, failed instrumentation or a failed child makes
the observation `INVALID`.

`build/test-performance-memory.ps1` checks JFR high-water reduction, missing
evidence, argv quoting, real Win32 memory counters, stream retention, timeout
termination, and descendant capture. These are harness smoke tests, not normative
performance evidence.
