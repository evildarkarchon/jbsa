# Fallout 4 DDS BA2 slice

Issue [#40](https://github.com/evildarkarchon/jbsa/issues/40) implements the
`BTDX` / `DX10` version 1 zlib path. The requirements registry remains immutable
because the CV1 specification identity binds its bytes. This supplemental trace
records the slice without changing accepted case identities or goldens.

| Requirement | Slice responsibility |
| --- | --- |
| JBSA-DX10-001–008 | Version 1 selector, texture/chunk records, independent zlib framing, mip ranges, mandatory compressed output, bounded validation/diagnostics, exact sharing and whole-entry splitting |
| JBSA-DDS-001–006 | Internal legacy/DX10/Xbox envelopes, explicit encode target, format derivation, checked dimensions, 24-bit normalization and chunk count |
| JBSA-DDS-008–013 | Canonical PC/Xbox headers, exact reconstructed payload, 25-format writable allow-list and independently dimension-derived mip spans |
| JBSA-CODEC-004 | Pure Java internal DDS implementation; no DDS provider seam or pixel decoder |
| JBSA-CLI-004/005 | `-fo4dds` selects PC and zlib even when `-z` is omitted |
| JBSA-CLI-009/010/011/014 | DDS metadata, diagnostics, stream placement and operation outcomes through the existing CLI |
| JBSA-COMPAT-006/007 | Explicit reconstruction target wins over the immutable profile's `_xbox.` filename inference; every profile prohibits stored DDS output |
| JBSA-CONF-001–003/005–014/016–019 | Immutable CV1 identities, qualified oracle, scoped fixtures, semantic and bidirectional comparisons, diagnostics, DirectXTex and evidence integrity |
| JBSA-PERF-001–003/005–013 | Exact targeted impact, runtime/corpus bindings, prerequisites and measurement protocol |
| JBSA-PERF-014–019/022 | Throughput, random access, peak memory, output size, scaling and retained evidence; release qualification remains separately gated |

`JBSA-DDS-007` is retired and must not be implemented. Its replacement,
`JBSA-DDS-013`, rounds each BC mip to physical blocks and clamps width and height
independently. General arrays and volumes are explicitly unsupported: only one
2D array item with depth one, including a six-face cubemap, is representable.
Other BA2 versions remain outside this issue's independently mergeable outcome.

DDS BA2 packing requires every selected final entry to have a case-insensitive
`.dds` extension and a valid DDS envelope. A non-DDS entry fails before
destination publication, including when it is supplied through an archive source
or generated channel. Inclusion masks still select the final source set.

Production DDS handling is pure Java. DirectXTex is a build-time independent
validator launched as the external `texdiag.exe` process; it is not accessed by
JNI or FFM and is not shipped as a runtime dependency.

## Independent development evidence

The scanner in `build/validate-dds-wire.py` is derived from the permanent
specification and imports neither JBSA nor the Reference Snapshot. It checks
canonical named ASCII version 1 archives, all supported format sizes, exact
chunk mip ranges, hashes, physical spans and complete zlib consumption. It also
checks extracted DDS envelopes and dimensions-derived opaque payload sizes.
It is deliberately bounded to 256 MiB and is not a full independent pixel decoder.

`build/generate-dds-fixtures.py` creates 33 project-authored CC0 inputs covering
all 25 writable formats, small and odd BC blocks, non-square mip chains,
one through four chunks and a cubemap. Inputs intentionally use DX10 headers;
comparisons must use canonical reconstruction and opaque payload bytes, not
assume preservation of those source headers.

```powershell
python build/test-dds-wire.py
python build/run-dds-differential.py --output target/dds-differential-1 `
  --texdiag target/directxtex-may2026/texdiag.exe
```

The differential command requires packaged library and CLI JARs and the pinned
local oracle. It retains streams, commands, tool/artifact digests, source and
decoded projections, canonical-byte comparisons, output sizes and development
durations. It invokes the public CLI and the digest-checking oracle adapter.
Failures remain visible in `report.json`; it cannot mint or approve CV1 goldens.

The official Microsoft DirectXTex `may2026` release's `texdiag.exe` has SHA-256
`411c303c98ba73e4423376f717ac139347dd749bf80a7fc1a22368ab1088ff56`.
The release asset digest was verified before use. On 2026-09-08, `texdiag info`
accepted all 33 generated source fixtures. Reconstructed product outputs require
their own retained validator observations; source acceptance alone does not
establish reconstruction conformance. The executable stays under ignored `target/`.

The authoritative catalog has 47 `fo4-dx10-v1` cells, including unsupported codec
negatives, all malformed disposition classes, target matches/mismatches and
targeted DDS boundary scenarios. Development tests and differential evidence do
not constitute all 47 accepted CV1 observations. The canonical harness remains
the authority for a complete CV1 claim and separate reviewed goldens are required.

The [2026-09-08 evidence summary](evidence/issue40-dds-checkpoint.json) retains
artifact and validator digests, all 47 explicit CV1 statuses, and the development
observations. Both differential directions preserve all 33 opaque payloads and
metadata projections; DirectXTex accepts all 33 JBSA reconstructions. Thirty
reconstructed headers match the oracle byte-for-byte. Three differ only in the
four-byte top-level linear-size field: `1x1` BC1 is 8 bytes rather than the
oracle's zero, `5x7` BC7 is 64 rather than 35, and `513x515` BC1 is 133128 rather
than 132097. These are the exact block-rounding corrections mandated by
`JBSA-DDS-009`. The report preserves raw equality as false and only classifies
that precisely checked discrepancy as specification-governed; it does not award
CV1 qualification or ignore any other differing byte.

## Targeted performance qualification

The current PV1 inventory assigns 84 cases to `fo4-dx10-v1`: 15 each for pack
and unpack scaling; ten each for pack memory, pack output, pack throughput,
unpack memory and unpack throughput; three random-payload cases and one
random-metadata case. DDS workloads include the 256-texture 2 GiB
`dds-mipmapped` corpus plus DDS-specialized bulk, metadata and mixed workloads.

```powershell
python build/prepare-dds-performance-impact.py `
  --catalog target/performance-catalog.json --output target/dds-impact.json
pwsh -File build/run-performance.ps1 -Mode targeted `
  -ConfigurationPath target/performance-run.json -ImpactPath target/dds-impact.json `
  -OutputDirectory target/pv1-dds-001
```

The impact generator also accepts the committed unbound inventory for planning;
such a manifest cannot qualify runtime execution. The actual run requires the
bound release profile, shipping launcher and JMH artifacts, passing applicable
CV1 prerequisites, corpus and oracle inputs, independent expected projections,
and environment attestation described in [the performance harness](performance.md).

The 2026-09-08 environment probe found NTFS, 27.4% free space, the High performance
plan and active security software, but 16.86% CPU while concurrent development
was active. No idle/no-concurrent-work attestation was established. The inventory
and DDS CV1 prerequisites were not yet bound. These are explicit missing PV1
qualification inputs; development durations must not be relabelled as passing
peak-memory, random-access or comparative throughput qualification.

The opt-in `DdsPerformanceCheckpointIT` measures the public library on the 33
generated inputs (4,562,004 bytes). Run it after generating
`target/dds-validator-fixtures` with `-Dit.test=DdsPerformanceCheckpointIT`,
`-Dfailsafe.failIfNoSpecifiedTests=false`, and `-Djbsa.dds.performance=true`.
It discards one warmup and retains three rounds. The recorded rounds pack to
18,439 bytes in 55–59 ms, reconstruct in 31–34 ms, inspect in 0.98–1.49 ms, and
perform 64 fresh entry prefix reads in 1.17–1.32 ms. Summed heap-pool peaks are
97–190 MB; these are not simultaneous live-heap or process-memory peaks.
Every extracted opaque payload is compared outside the timed operations.

## Implementation verification

On 2026-09-08, `mvnw.cmd -B -ntp -C clean verify` passed across all seven
reactor modules. The 47 focused DDS library tests, CLI process tests, and five
independent scanner tests passed. The final rebuilt JARs also passed the
33-input bidirectional differential and DirectXTex reconstruction checks; their
digests are recorded in the evidence summary. Optional local-corpus and formal
performance tests remain opt-in and are not represented as completed by this
build.

Separate standards and specification reviews found a whole-source `u32` limit
that needed to apply per chunk and missing helper documentation; both were
corrected. Normalized decoded-size accounting is covered by a cumulative
multi-entry limit test. Shared BA2 diagnostic/name parsing remains duplicated
between family readers as a future refactoring opportunity. This commit is an
implementation checkpoint, not a declaration that the outstanding CV1/PV1 merge
qualification gates have passed.
