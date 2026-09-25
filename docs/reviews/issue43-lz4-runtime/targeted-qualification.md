# Issue 43 targeted LZ4 qualification

The Windows x64 adapter qualification passed on 2026-09-11 at 02:56 UTC:
39 measured observations, exact decoded-byte comparisons, deterministic
compressed-byte comparisons across three repetitions, insufficient-credit
rejection, and cancellation cleanup. This evidence qualifies the internal
dispatch envelope. It is not an Archive Family conformance claim or the formal
Performance-v1 release gate.

The runtime-loaded profile is `jbsa-lz4-v1`, SHA-256
`f7221b24458804a454716fb89bbad9f6b3947f8484158e3950e1608e93b4732e`.
[The captured manifest](qualified-codec-profile.json), [environment](environment.txt),
and [individual measurements](measurements.tsv) are retained beside this report.
The manifest pins LWJGL 3.4.3 and upstream LZ4 1.10.0. The host was Windows 11
amd64, OpenJDK 25.0.4.1+1-LTS, with 16 available processors.

## Method and scope

`Lz4Qualification` is an explicitly selected JUnit helper; its name keeps this
measurement workload out of ordinary Surefire discovery. It uses synthetic
inputs of 0, 65,536, and 16,777,216 bytes. Each size is tested both with
`java.util.Random(43)` bytes and the repeating sequence `byte(i % 251)`.
Every input is encoded and decoded three times with separate native state and
resource ledgers. SHA-256 input/output identities are recorded per observation;
the helper also compares the complete decoded and repeated encoded arrays.

Three additional observations decode a 16 MiB `Random(44)` input encoded directly
by upstream `LZ4F_compressFrame` with maximum 4 MiB blocks. These establish the
decoder's larger-block path independently of this release's 64 KiB frame encoder.
The zero encode timing in those rows means unmeasured, not instantaneous.

Timings include adapter allocation, native calls, borrowed in-memory source and
sink copies, and release. Hashing and Windows process inspection happen outside
the timing. The first repetition is retained, with no claim of JIT stabilization.
There are no forked repetitions, confidence intervals, disk I/O, reference-tool
comparison, or whole-archive workloads. The ordinary LWJGL `Unsafe` deprecation
warning was present; native loading and operations completed successfully.

## Observed throughput and output size

Values below are medians of three observations for 16 MiB inputs. Throughput is
logical decoded MiB per second; small synthetic repeated content particularly
favors raw compression and should not be treated as a game-archive workload.

| Profile / corpus | Stored bytes | Encode MiB/s | Decode MiB/s |
| --- | ---: | ---: | ---: |
| Raw / repeated 251-byte sequence | 66,053 | 2,478.929 | 2,447.793 |
| Raw / random seed 43 | 16,842,862 | 51.994 | 1,996.481 |
| Frame / repeated 251-byte sequence | 69,394 | 62.665 | 2,460.365 |
| Frame / random seed 43 | 16,778,263 | 45.703 | 1,733.835 |
| Upstream 4 MiB frame blocks / random seed 44 | 16,777,243 | unmeasured | 4,323.273 |

The raw random case expands beyond the decoded dispatch limit. Decoding succeeds
under the separate compressed-bound limit, so the envelope permits the encoder's
own incompressible output. All three repetitions of each encoder case had equal
compressed bytes. This establishes repeatability of these cases only, without
claiming cross-provider compressed Binary Conformance.

## Memory and cancellation envelope

Hard admission limits are size-based: raw decoded blocks are at most 16 MiB and
stored blocks at most `compressBound(16 MiB) = 16,843,025` bytes. Raw blocks remain
unsplittable within the adapter. Frame dispatch uses 64 KiB input/output windows;
the upstream decoder can internally buffer a maximum 4 MiB block.

Each measurement verifies complete credit return. An additional call with one
byte less native credit must fail with `POLICY` before any sink effect. The
measured maximum-size raw encoder reserves 34,144,561 native bytes; the random
raw decoder reserves 33,620,094. Frame encoding reserves 2,293,760 native bytes
and frame decoding 9,633,792 regardless of logical stream length. Each adapter
also reserves 4,096 heap bytes. These are conservative admission envelopes,
not measurements of upstream allocation high-water marks.

Windows `PeakWorkingSet64` reached 557,772,800 bytes during this process. This
lifetime process peak includes the JVM, synthetic corpus, retained result arrays,
upstream fixture creation, and all preceding cases. It cannot be attributed to a
single adapter, used as a native-only peak, or interpreted as evidence of a leak.
Credit-return assertions and independently closed per-call arenas qualify
resource ownership; a release memory study remains a separate task.

The largest observed checkpoint gap was 312.840 ms for raw encoding, 4.582 ms for
raw decoding, 2.405 ms for frame encoding, and 0.101 ms for frame decoding.
Raw HC compression is one non-interruptible native call bounded by the 16 MiB
dispatch limit. A stop requested immediately after its pre-call checkpoint can
therefore wait approximately 313 ms on this host before observation. Frame
checkpoints occur between bounded windows. Gaps conservatively include Java
callbacks and scheduling pauses; they are observations, not worst-case latency
guarantees or cancellation percentiles. No numerical cancellation deadline is
imposed by JBSA-SCHED-010, and no unmeasured real-time claim is made here.

The helper throws a cancellation exception at a later checkpoint in substantial
streams, asserts that the same exception returns, and reserves the entire
original ledger afterward. The native call is allowed to settle; no interrupt,
executor, or unsafe native cancellation is used.

## Reproduction

From the repository root on the qualified Windows x64 host:

```powershell
$env:JAVA_HOME = 'C:/OpenJDK/jdk-25'
.\mvnw.cmd -o -B -ntp -pl jbsa -am '-Dtest=Lz4Qualification' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

The helper rewrites its evidence files. Surefire supplies the repository's native
access grants for `io.github.evildarkarchon.jbsa`, `org.lwjgl`, and `org.lwjgl.lz4`.
The run finished with one qualification test, zero failures, and zero errors.
The codec seam's malformed-data and independently authored wire fixture tests
supplement this synthetic measurement run; native launch
qualification is documented separately by the issue implementation.
