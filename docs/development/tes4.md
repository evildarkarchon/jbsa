# TES4 / Oblivion BSA slice

Issue #38 implements the versioned-BSA 0x67 walking slice through the public
library and CLI. The independent evidence added here covers stored, zlib and
mixed payload framing, canonical stored output, decoded-size and truncation
rejection, extraction, and opt-in differential cross-decoding.

`PackOptions.entryCompression` is an optional immutable map from
`NormalizedNameIdentity` to a concrete compression choice. It applies after
overlays and filtering, overrides the request-wide choice for that entry, and
rejects unmatched names before payload access. The original six-argument
constructor supplies an empty map, preserving existing defaults. This is a
format-neutral API: each Archive Family enforces its codec matrix, and TES3
rejects nonempty overrides. TES4 permits `STORED` and `ZLIB`, including both in
one output archive. `FAMILY_DEFAULT` is a request-wide default, not a concrete
per-entry choice. An override cannot select an algorithm incompatible with the
archive's wire version or method; in particular, this does not permit mixing
zlib and LZ4 within a Starfield archive. Canonical DDS rules still require
compressed texture chunks: Fallout 4 and Starfield crash when BA2 DDS archives
contain files using `STORED`. Every future DDS encoder must reject that choice
before output effects, including per-entry overrides. The CLI continues to use
its request-wide `-z` switch.

## Reproduce slice evidence

Use Java 25 (`C:\OpenJDK\jdk-25` on the development machine), PowerShell 7,
Python 3.11 or later, and the Maven wrapper:

```powershell
.\mvnw.cmd -B -ntp -C '-Dit.test=BsaConformanceIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
.\mvnw.cmd -B -ntp -C '-Dit.test=BsaConformanceIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djbsa.bsa.local=true' verify
```

The second command requires the locally provisioned, digest-pinned
[`BSArch.exe`](../reference/bsarch-oracle.md). Hosted tests never execute it.
Observations and raw streams are retained under `target/bsa-local-evidence`
and `target/bsa-validator-evidence`. Each direction compares both payloads,
and both candidate and oracle archives pass the separate Python wire scanner.
The scanner is project-authored from the specification; it imports neither
the product nor the fixture generator and has a bounded 16 MiB input scope.

The [fixture inventory](../../tests/fixtures/tes4/README.md) carries CC0-1.0
provenance and decoded SHA-256 digests. Its regeneration test detects missing,
extra or changed vectors. These checks do not create or replace CV1 goldens.

## Requirement and case boundaries

Evidence uses the packaged `META-INF/jbsa-codec-profile.json` identity
`jbsa-jdk-zlib-v1`, SHA-256
`b9515f305ba223111b790ad06c98580360ac85c40235258316aad2ba001a3fda`.
The integration test verifies the packaged resource digest; checkpoint conditions
record that same profile identity.

The slice exercises BSA-001–003, 005–009, 012–015 and the 0x67 prohibition in
BSA-011; CODEC-001–003, 005–006, 008–010, 012–013 govern its internal profile,
bounded zlib and failures. CLI-004/005 map `-tes4` and zlib; CLI-006/009 govern
split controls and inspection output. CONF-010–014 govern semantic,
differential, malformed and independent evidence. Identifiers here have the
`JBSA-` prefix in the [requirements registry](../spec/requirements.yaml).

The active CV1 catalog contains 32 approved `bsa-067` successor cases: twelve base
decode/encode codec cells, eighteen malformed decode cases, unsafe-name
extraction, and the XML scenario. The [approval record](../reviews/issue38-cv1/activation.json)
binds their reviewed golden bytes and activation. Runtime registrations are
regenerated against the packaged artifacts as described in the
[review guide](../reviews/issue38-cv1/README.md). This scoped evidence does not
claim that unrelated archive families or the complete release have conformed.

The reference's automatic 0x67 flags contain the documented embedded-name
contradiction. Oracle tests compare semantic cross-decoding rather than those
default flag bytes. Safe JBSA output keeps `0x0100` clear. Compressed fixture
bytes use Python zlib and are not a cross-provider Binary Conformance claim.
Non-ASCII and collision Binary Conformance remain fixture-dependent.

## Performance checkpoint prerequisites

The development run on 2026-09-08 passed all payload comparisons. Its
[six raw measurement rows](tes4-checkpoint.csv) record a 16 MiB deterministic
corpus on Windows 11, Temurin 25.0.4.1+1-LTS, and 16 logical processors.
Median stored pack/extract times were 0.0348/0.0275 seconds; zlib times were
0.2069/0.0422 seconds. Output was 16,777,484 stored bytes and 8,399,716 zlib bytes.
The run uses codec profile `jbsa-jdk-zlib-v1`, SHA-256
`b9515f305ba223111b790ad06c98580360ac85c40235258316aad2ba001a3fda`.

The task's development checkpoint runs on the current machine, as explicitly
authorized by the user, without idle or reboot gating:

```powershell
.\mvnw.cmd -B -ntp -C '-Dit.test=BsaPerformanceCheckpointIT' '-Dfailsafe.failIfNoSpecifiedTests=false' '-Djbsa.bsa.performance=true' verify
```

It records stored/zlib pack and extraction times, output sizes, 64 entry-prefix
reads, and summed heap-pool peaks for a deterministic 16 MiB mixed corpus under
`target/bsa-performance-checkpoint`. One warmup is discarded and three rounds
are retained. Exact extracted bytes are checked outside timing. A 64 MiB pack
scratch ceiling is enforced; observed scratch peak is not measured. The
conditions file records current-machine and measurement limitations, including
concurrent work and the absence of a formal PV1 or reference-comparison claim.

The PV1 inventory contains 249 `bsa-067` cases: 36 each for pack memory, output
size and throughput and unpack memory and throughput; 30 each for pack and
unpack scaling; six random-payload and three random-metadata cases. Workloads
are bulk-compressible, bulk-incompressible, metadata-100k, mixed-10k and
shared-content. They remain unbound assignments until supplied with the exact
candidate, runtime codec profile, passed applicable CV1 cases, corpus and
independent expected projections.

Run an explicit targeted checkpoint using the
[performance harness](performance.md), selecting the complete `bsa-067` case
set in an impact manifest. Qualification additionally requires its pinned
Temurin `25.0.4.1+1` distribution, Windows x64/NTFS machine checks and truthful
reboot, idle and no-concurrent-workload attestations. The slice tests neither
establish these prerequisites nor claim PV1 qualification. An absent prerequisite
must remain `INVALID`; smoke timings cannot substitute for qualified evidence.
