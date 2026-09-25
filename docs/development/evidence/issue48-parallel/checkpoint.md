# Issue 48 parallel-operation checkpoint

The raw [scaling observations](performance-checkpoint.json) were recorded on 2026-09-25
with Java 25.0.4.1 on Windows x64. The JVM reported 16 available processors. Each run
packed 32 generated one-MiB entries into Fallout 4 General BA2 with JDK zlib,
sharing disabled, and one output part. Three rounds ran at each explicit worker
limit; the table shows the median wall time and throughput and the largest sampled
additional heap use across those rounds.

| Workers | Median wall time (ms) | Median throughput (MiB/s) | Max additional heap (MiB) |
| ---: | ---: | ---: | ---: |
| 1 | 225 | 142.14 | 21.00 |
| 2 | 146 | 219.69 | 19.52 |
| 4 | 91 | 352.29 | 19.81 |
| 8 | 78 | 411.46 | 19.45 |
| 16 | 80 | 401.55 | 20.06 |

All 15 outputs were 173,070 bytes with SHA-256
`0635653d78d32308b992b429556724fc8142f4cd281ecc296d25cb474736329b`.
The public pack test also held the first source while later work completed and
verified that a two-worker invocation opened no more than four sources before
the first result could be consumed. The high-worker spill test exercised 16
workers with retained private spools and verified that coordinator scratch
could still grow. A tight-scratch test compared one and two workers and verified
that both succeeded with identical bytes. Public extraction tests covered
ordered stored/compressed results, forty staged files with returned handle
credits, blocking and failing observers, and cancellation while workers were
active. Public pack tests also covered caller interruption, a structured
worker exception, and cancellation during parallel LZ4 frame encoding.
The native-call race test observed a named worker inside `LZ4F_compressUpdate`,
sampled caller cancellation while native work remained active, and verified
that no archive was published; three additional reruns passed. Acceptance is
linearized just after the supplier returns, so this public-API test does not
pin the native call through that exact instruction. The runner's forced-stop
path discards a pending native result even if it finishes during acceptance. A
nested-operation test verified that a progress observer cannot restore an outer
caller's deferred interrupt before its publication finishes.
Another public test started two simultaneous two-worker packs and observed all
four source factories active before releasing them, demonstrating independent
operation pools.

The probe samples JVM heap every five milliseconds and records a pre-run heap
baseline; it does not measure process working set, native codec allocation, or
on-disk scratch peaks. Timings come from a local development run, with no
released baseline or formal Assurance v2 performance gate. They record this
issue's implementation checkpoint; release performance qualification remains
the separate release-gate work in issue 55.
