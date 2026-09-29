# Issue 52 Final Profile Gate: jlibdeflate deferred

Decision date: 2026-09-29. Candidate: `com.fulcrumgenomics:jlibdeflate:0.1.0`
(JAR SHA-256 `c20407700e94307b80b8ed832be3fbc52370e6c3f826fe8b91b89d9db1910ef2`), bundling
libdeflate 1.25, at level 12, behind a qualification-only whole-buffer adapter. Baseline: the
release profile `jbsa-lz4-v1`, SHA-256
`f7221b24458804a454716fb89bbad9f6b3947f8484158e3950e1608e93b4732e`, whose zlib provider is Java 25
`Deflater`/`Inflater` at level 9.

## Decision

**Promotion is deferred.** The native-loading and packaging gates fail. The encode performance
and cancellation-latency evidence does not support a whole-buffer encode dispatch at level 12.
Under [JBSA-REL-008](../../../spec/release-gates.md#jbsa-rel-008) and
[JBSA-CODEC-007](../../../spec/codecs.md#jbsa-codec-007), a single failed gate is enough to defer
promotion.

The selected standard zlib dispatch is the already-qualified baseline, unchanged:

| Decision item | Selected value |
| --- | --- |
| Codec profile | `jbsa-lz4-v1`, SHA-256 `f7221b24…732e`; manifest bytes unchanged |
| zlib encode | JDK `Deflater`, level 9, `DEFAULT_STRATEGY`, RFC 1950, streaming in 65,536-byte windows at every size |
| zlib decode | JDK `Inflater`, streaming in 65,536-byte windows at every size |
| Whole-buffer input-size threshold | None. No whole-buffer zlib path is selected. |
| Memory-credit dispatch threshold | None. JDK credits stay at 132,096/524,288 bytes (encode heap/native) and 66,560/65,536 bytes (decode heap/native). |
| Native zlib provider and fallback | None. With no native zlib decoder, the JBSA-CODEC-009 fallback allowance is unused. |
| `native_configuration.jlibdeflate` | `not-promoted` (unchanged) |

No new codec profile was created. The unchanged manifest therefore needs no rerun of Assurance
Scenarios or Performance Lanes. [packaging.tsv](packaging.tsv) re-verifies its bytes against the
committed `.sha256` sidecar.

## Gate results

| Gate | Result | Evidence |
| --- | --- | --- |
| Semantic Decode Conformance | Pass | [conformance.tsv](conformance.tsv) |
| Semantic Encode Conformance | Pass | [conformance.tsv](conformance.tsv) |
| Malformed input | Kind pass; identifier gap | [malformed.tsv](malformed.tsv) |
| Provider fallback | Pass (candidate rule) | `JlibdeflateZlibTest`, [malformed.tsv](malformed.tsv) |
| Deterministic output | Pass for measured cases | [conformance.tsv](conformance.tsv), [native-loading.tsv](native-loading.tsv) |
| Memory credits | Pass (admission) | [native-state.tsv](native-state.tsv), [measurements.tsv](measurements.tsv) |
| Performance | Decode pass; encode fail | [summary.tsv](summary.tsv) |
| Native loading | **Fail** | [native-loading.tsv](native-loading.tsv) |
| Packaging | **Fail** | [packaging.tsv](packaging.tsv) |
| Notices | **Fail as shipped** | [packaging.tsv](packaging.tsv) |

Detail for each gate:

- **Semantic Decode and Encode Conformance.** Five corpora were run at 0 B, 1 B, 4 KiB, 64 KiB,
  1 MiB, 8 MiB, 8 MiB + 1 and 16 MiB. JDK exactly decodes every candidate stream, the candidate
  exactly decodes every JDK stream, and both round-trip.
- **Malformed input.** The valid control passes, and all 14 malformed cases match the JDK Failure
  Kind (`FORMAT`) and publish no bytes.
  Three truncated-trailer cases report `codec.size-mismatch` where JDK reports
  `codec.invalid-data`.
- **Provider fallback.** Decode falls back to JDK only after candidate preflight reports
  `CAPABILITY`. Encode pins the candidate before reading or writing. Invalid data is never retried.
- **Deterministic output.** Three in-process repetitions are identical, four concurrent workers are
  identical, and the bytes are identical across fresh processes.
- **Memory credits.** Admission is sound.
  - One byte less credit is refused with `POLICY` before any effect, and cancellation returns every
    credit.
  - Measured heap allocation stays within 614 bytes of bookkeeping above the whole-buffer credit.
    The metric is cumulative thread allocation, not peak live heap. For the whole-buffer candidate
    the two coincide. JDK rows exceed their resident-window credits by up to 90,848 bytes of
    short-lived per-window wrappers and harness channel buffers that grow with the window count.
    This metric therefore neither confirms nor refutes the JDK credits.
  - The level-12 compressor holds 9,033,216 committed bytes per instance (credit 9,437,184), which
    is 17 times the JDK encode native credit.
- **Performance.** Decode is 1.24–4.82 times faster. Level-12 encode is 0.18–0.58 times the JDK's
  speed on the repeated, text and mixed corpora; float-mesh is 0.89–1.15× and random 0.66–1.04×.
- **Native loading.** Three DLL copies load per process, three temporary DLLs are left behind per
  process, and a caller-supplied library path is honored.
- **Packaging.** There is no module name. The JAR also carries four non-Windows native payloads.
- **Notices.** The JAR contains no license or notice text.

## Findings

### Native loading (blocking)

Five fresh-JVM probes ran with an isolated `java.io.tmpdir` ([native-loading.tsv](native-loading.tsv)).

- **Triple load and leak.** Every default launch mapped three separate `jlibdeflate-*.dll` copies,
  each at a distinct handle, inside a single `NativeLoader.load()` call. All three files remained
  after the process exited.
  - Windows cannot delete a mapped DLL, and `deleteOnExit` runs while the DLL is still mapped.
  - Each CLI invocation would therefore leave about 258 KB of undeletable temporary files that
    nothing ever reclaims.
  - Disassembly and `-Xlog:library` show the extraction path running repeatedly before `loaded` is
    set. That is consistent with re-entrant class initialization; the effect was measured, while the
    mechanism is inferred.
- **Caller-supplied DLL path.** The provider reads the system property `jlibdeflate.library.path`
  before extracting from its JAR. With that property pointing to a copy of the DLL, the probe loaded
  it from the caller's directory. With it pointing to an empty directory, loading failed with
  `UnsatisfiedLinkError`.
  - [JBSA-CODEC-011](../../../spec/codecs.md#jbsa-codec-011) forbids accepting a caller-supplied DLL
    path.
  - The candidate adapter rejects the property as a `CAPABILITY` failure (`native-configuration`),
    the same way `Lz4Runtime` rejects LWJGL's overrides. That mitigates the rule only for calls that
    go through JBSA preflight.
- **No version query.** jlibdeflate exposes no runtime version check comparable to
  `LZ4_versionNumber`. Provider identity would rest on dependency verification alone.
- **Native access.** Without `--enable-native-access`, Java 25 prints the restricted-method warning.
  The application image would need a class-path grant (`ALL-UNNAMED`).

### Packaging and notices (blocking)

[packaging.tsv](packaging.tsv) records these facts:

- **No module name.** The JAR has neither `module-info.class` nor `Automatic-Module-Name`. Gradle's
  module-path inference therefore puts it on the class path. The white-box test module reaches it
  only through `--add-reads io.github.evildarkarchon.jbsa=ALL-UNNAMED`, and the named `jbsa` module
  cannot `requires` it by a stable name.
- **Extra native payloads.** Besides the approved-candidate Windows DLL, the JAR carries native
  payloads for Linux x86-64, Linux AArch64, macOS x86-64 and macOS AArch64. JBSA-LIC-008 requires
  every native payload in a release artifact to be inventoried and approved. Only the Windows DLL is
  inventoried.
- **No license text.** The JAR contains no license or notice file. Release packaging would have to
  supply the jlibdeflate and libdeflate MIT texts itself.
- **Absent from release inputs.** The candidate is locked only on `:jbsa` test classpaths. It is
  absent from the `jbsa-cli` and `jbsa-dist` locks and from the Windows launch policy, as required
  before promotion.

### Performance and memory

These are medians of five measured rounds after one discarded warmup, taken at the codec seam with
in-memory sources. Throughput counts decoded MiB per second. [summary.tsv](summary.tsv) holds every
size, and [measurements.tsv](measurements.tsv) holds every observation. The 16 MiB results:

| Corpus | Encode vs. JDK | Decode vs. JDK | Stored size vs. JDK | Longest candidate encode checkpoint gap |
| --- | ---: | ---: | ---: | ---: |
| repeated-251 | 0.21× | 4.82× | 1.011 | 338 ms |
| text-seed52 | 0.58× | 3.59× | 0.946 | 3,036 ms |
| float-mesh-seed52 | 1.10× | 1.24× | 0.980 | 559 ms |
| mixed-seed52 | 0.37× | 1.56× | 0.998 | 1,622 ms |
| random-seed52 | 1.04× | 2.02× | 1.000 | 350 ms |

- **Decode.** Decode wins at every measured size, from 1.24× to 4.82×.
- **Encode.** Level-12 encode is slower than the JDK on every compressible corpus except the smooth
  float stream. The gain is only 5.4–7.4% smaller output on text (at 16 MiB and 64 KiB), and
  repeated data even grows by about 1%.
- **Cancellation.** A whole-buffer encode is one non-interruptible native call, so a stop request
  can wait about 3 s on 16 MiB of text. JDK streaming observes a checkpoint on every window: its
  longest measured gap was 11.7 ms for encode and 3.9 ms for decode.
- **Memory.** Each encode worker needs about 9 MiB of native state, and every whole-buffer call
  needs heap for the complete source and output. That is an admission cost the JDK path does not
  have.

No throughput was measured below 64 KiB. Sizes of 0 B, 1 B and 4 KiB have conformance evidence
only, so any future whole-buffer threshold would need small-size measurements first. Deferral makes
that moot for this decision.

A decode-only whole-buffer dispatch would have performance and memory support on this evidence.
It remains blocked by the native-loading and packaging failures above, which apply to decode as
well.

### Malformed-input diagnostic identity

jlibdeflate reports a zlib stream missing one or two trailer bytes with the same message ("Output
buffer too small") as a genuine over-expansion. A three-byte truncation instead produces the
invalid-data message. The provider message is jlibdeflate's only error channel, and a bounded
single call cannot tell these cases apart.

The candidate therefore reports `codec.size-mismatch` for the three truncated-trailer cases, where
JDK reports `codec.invalid-data`. The Failure Kind still matches in every case
([JBSA-CODEC-012](../../../spec/codecs.md#jbsa-codec-012)), and no bytes are published on failure.
The identifier divergence would still need its own disposition before any promotion.

The candidate also has to use jlibdeflate's `*Ex` methods with a one-byte probe. The exact-size
`zlibDecompress` silently accepts short output and trailing bytes.

### libdeflate 1.25 versus Reference 1.24

The pinned oracle archives embed BSArch's libdeflate 1.24 level-12 streams: issue 38 (Oblivion),
issue 39 (Fallout 4 GNRL) and issue 42 (FO3). [oracle.tsv](oracle.tsv) records the comparison.

| Input | libdeflate 1.25 level 12 | JDK level 9 |
| --- | --- | --- |
| `A*1024` | same bytes as the reference | same bytes as the reference |
| `000102ff` | same bytes as the reference | different |

This byte identity covers these two pinned cases only; no general cross-version claim is made.
[JBSA-CODEC-007](../../../spec/codecs.md#jbsa-codec-007) forbids retaining 1.24 merely to pursue it,
and this evidence is not a reason to promote.

## Scope and limits

These measurements are codec-seam engineering evidence on one Windows 11 x64 host with 16
processors, running Eclipse Temurin 25.0.4.1+1-LTS with a 512 MiB heap
([environment.txt](environment.txt)). They are not the release Performance Lanes of
[JBSA-ASR-005](../../../spec/assurance-v2.md#jbsa-asr-005), which issue #55 runs. They include no
archive I/O, no confidence intervals, and no second CPU.

Committed-memory deltas are coarse process observations and serve as a check on the declared
credits, not as allocator high-water marks. The corpora are synthetic, fixed-seed shapes defined in
`JlibdeflateLaunchProbe.corpus`.

## Requalification triggers

Any of these reopens the gate. Each requires rerunning this lane, and a later promotion requires a
new digest-identified profile together with the impacted Assurance Scenarios and Performance Lanes
(a codec change selects the `full` tier):

- A new jlibdeflate or libdeflate version, or a different build of the pinned bytes.
- A provider loader that maps one library per process, reuses or reclaims its extraction, and does
  not honor a caller-supplied library path.
- A stable module name (`module-info` or `Automatic-Module-Name`).
- A Windows-only artifact, or an approved inventory for every bundled native payload.
- Shipped or supplied license texts for jlibdeflate and libdeflate.
- A proposal for a different level, a decode-only dispatch, or a whole-buffer threshold.
- A change of Java runtime or JDK zlib behavior, because that moves the baseline comparator.
- Release Performance Lane results (#55) showing zlib decode as the dominant cost.

## Reproduction

The lane is opt-in because loading the provider leaves undeletable DLLs in the Windows temporary
directory. From the repository root on Windows x64:

```powershell
.\gradlew.bat "-Djbsa.zlib.qualification=true" :jbsa:test --tests '*JlibdeflateQualificationTest'
```

It rewrites every `.tsv` and `environment.txt` file in this directory. The ordinary
`JlibdeflateZlibTest` checks run in every build without loading the provider. They cover
admission, credits, override rejection, and fallback.
