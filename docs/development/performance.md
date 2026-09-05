# Local performance-v1 harness

Issue #32 implements the foundation for [performance-v1](../spec/performance-v1.md).
The permanent registry assigns `JBSA-PERF-001`–`003` and `005`–`013` to this issue.
The numerical rules in `014`–`019` are executable now; passing release results,
baseline establishment and release attachment publication remain issue #55 gates.
Provider promotion and Binary Conformance remain separate issue #52 evidence.
The [supplemental traceability map](../../tests/performance/requirements.json)
links these requirements to harness checks. The existing CV1 catalog pins the
entire requirements registry, so test-only traceability is recorded here without
changing that accepted specification-set identity.

The checked-in catalog is an **unbound assignment inventory**. The archive slices
must supply their actual immutable runtime profile, conformed executable and input
bindings. Running an unbound inventory produces an `INVALID` row for every case.
Neither harness unit tests nor a successful JMH build claims qualification.

## Build and harness checks

Use Java 25, Maven 3.9.16 through the wrapper, PowerShell 7 and Python 3.11 or later.
Python uses only its standard library. Java source and tests compile during the
ordinary reactor build; the standalone JMH 1.37 JAR is build-only evidence tooling.

```powershell
.\mvnw.cmd -B -ntp -C clean verify
python -m unittest discover -s build/performance -p 'test_*.py'
.\build\test-performance-memory.ps1
```

These commands execute bounded harness checks with tiny project-authored inputs.
They do not generate the full corpus, execute archive benchmarks or set machine
performance configuration. GitHub Actions and ordinary `mvn verify` never execute
the qualification command. The command rejects hosted and self-hosted runner
environments before launching any measured executable.

## Corpus preparation

See [the corpus manifest guide](../../tests/performance/corpus/README.md). The six
committed compressed manifests enumerate exact paths, lengths, hashes, algorithms,
seeds, source proportions and structural templates. Generators stream bounded
blocks; corpus construction is compression-neutral and remains outside timing.

```powershell
python build/performance/corpus.py materialize `
  tests/performance/corpus/bulk-compressible.json.gz target/performance-inputs/bulk-compressible
python build/performance/corpus.py verify `
  tests/performance/corpus/bulk-compressible.json.gz target/performance-inputs/bulk-compressible
```

Materialization verifies existing bytes and writes missing files without replacing
mismatches. It rejects extra files, unexpected directories and filesystem links.
Full inputs remain under ignored `target/`; proprietary archives and the oracle
executable never enter the corpus or committed evidence.

DDS paths use byte-valid DDS source specializations; decode-only v7/v8 use pinned
medium projections. The exact manifest used is retained with the case. Source
extensions record the mixed workload's extension-policy coverage; DDS BA2's
mandatory compression still applies. Validators must establish the expected
Archive Family, chunk layout and stored/compressed mix before accepting the run.

## Bind an immutable profile and cases

`catalog.py --profiles` accepts the exact runtime profile JSON with this shape:

```json
{
  "profile_id": "release-owned-opaque-id",
  "codecs": {
    "stored": {"provider": "none", "version": "none", "configuration": {
      "parameters": {}, "size_dispatch": {}, "native_configuration": {}}},
    "zlib": {"provider": "jdk", "version": "25.0.4.1+1", "configuration": {
      "parameters": {}, "size_dispatch": {}, "native_configuration": {}}},
    "lz4-frame": {"provider": "lwjgl", "version": "3.4.3-lz4-1.10.0", "configuration": {
      "parameters": {}, "size_dispatch": {}, "native_configuration": {}}},
    "raw-lz4": {"provider": "lwjgl", "version": "3.4.3-lz4-1.10.0", "configuration": {
      "parameters": {}, "size_dispatch": {}, "native_configuration": {}}}
  }
}
```

The empty objects above illustrate the schema only; actual release settings must
record every parameter, dispatch rule and native configuration. The canonical
whole-profile SHA-256 becomes part of every case token and mapping. Changing a
profile creates new identities. A separately qualified provider uses its own
catalog and evidence, never a reused profile token.

```powershell
python build/performance/catalog.py --profiles target/runtime-profile.json `
  --output target/performance-catalog.json
```

A qualification configuration contains `catalog`, `protocol_sha256`,
`release_version`, `baseline_required`, `comparators`, `conformance_report`,
`environment_attestation`, `timeout_seconds`, and `cases`. File bindings throughout
are `{ "path": "repository-relative-or-absolute-path", "sha256": "64-lowercase-hex" }`.
The protocol is [protocol.json](../../tests/performance/protocol.json); its JVM ZIP
checksum was checked against Adoptium's official `jdk-25.0.4.1+1` release checksum.

Each comparator (`candidate`, `oracle`, and later `baseline`) binds its `launcher`,
complete `inventory` of distribution files, `production_arguments` array and
`worker_arguments` mapping for explicit `w2`/`w4`/`w8`/`w16` controls. The oracle
uses only `-mt:no`/`-mt:yes`. Java comparators also bind `jvm` (`distribution`,
`release`, `java`, `vm_library`, `jfr_tool`), `codec_profile`,
`codec_profile_sha256`, `provider_configurations` keyed by codec,
`conformance_artifacts`, `jmh_jar`, and `jmh_library_artifact`. The JMH library
binding must be part of the conformed candidate inventory, and every library class
must appear byte-identically in the standalone JMH JAR. The executing Java launcher and native VM
are compared with the pinned distribution ZIP, and actual CV1 candidate artifact
digests must occur in the verified executable distribution inventory.

Each `cases` registration contains:

- `case_id`, canonical `case_sha256`, and all applicable passed CV1 `prerequisites`;
- `corpus_manifest` binding and `source_directory` containing exactly that tree;
- for unpack, `oracle_archive` plus `oracle_archive_producer_sha256`;
- `binary_conformance` indicating the separate qualified binary gate;
- `validator` with bound `executable`, `inputs`, argument array and timeout;
- `expected_projection`, a bound independently reviewed semantic projection;
- for random access, the bindings described in [JMH integration](../../jbsa-benchmarks/README.md).

The validator receives `--case-id`, `--role`, and `--output` as distinct arguments.
It returns JSON `{case_id, outcome: "PASS"|"FAIL", projection: ...}`. The projection
must equal the bound expected projection; a bare success status cannot qualify an
output. Validator code, dependencies and expected projections are reviewed and
digest-bound trust inputs. Applicable conformance failures block all measurements.

No candidate archive implementation is substituted by the harness. The benchmark
service adapter calls the public library seam supplied by each implemented slice.

## Explicit local qualification

Before a full run, reboot, leave the machine idle for ten minutes, select High
performance, retain normal security software, and stop concurrent foreground,
build and synchronization work. `performance-environment.ps1 -VolumePath target`
prints live diagnostic data including the exact UTC boot time. The configuration
attests `boot_time`, `idle_seconds`, `no_concurrent_workload`,
`normal_security_configuration`, and `warmed_cache_protocol`. The runner measures
background CPU, filesystem, free space, power plan and active security state.
Failed or missing preconditions produce `INVALID`; it never changes these settings.

```powershell
.\build\run-performance.ps1 -Mode full `
  -ConfigurationPath target/performance-run.json -OutputDirectory target/pv1-full-001
.\build\run-performance.ps1 -Mode targeted `
  -ConfigurationPath target/performance-run.json -ImpactPath target/zlib-impact.json `
  -OutputDirectory target/pv1-zlib-001
```

Every evidence directory must be new and beneath repository `target/`. A targeted
impact manifest has `reason`, `selectors` and `case_ids`. Selectors are exact
matches on surface, workload, family/layout, codec/provider token, codec or provider;
the IDs must equal their complete union. Duplicate, missing and unknown cases are
rejected. Unavailable logical-processor counts are structurally N/A, excluded from
execution; all available scaling counts remain visible and independently gated.

Process cases discard two rounds, then retain seven balanced paired rounds and
extend to fifteen when confidence or dispersion requires it. Each invocation uses
fresh output, pre-read inputs and external monotonic creation-to-exit time. Output
validation, hashing and setup happen outside timing. Memory is measured separately
in five Job Object runs with 50 ms traces, final peaks and Java JFR summaries.
JMH retains all three forks and thirty measurement iterations per mode/artifact;
allocation profiling is a separate run. Scaling records speedup and efficiency
at 1/2/4/8/16 workers where available, including the above-eight regression gate.

## Evidence and lifecycle

`results.json` follows [result.schema.json](../../tests/performance/result.schema.json).
It retains identity mappings, comparator identities, CV1 prerequisites, environment,
round order, raw observations, paired ratios, confidence bounds, dispersion and
independent metric outcomes. `matrix.md` is the concise human view. Each case uses
a short digest-named directory to avoid Windows path-length failures. Raw streams,
JMH JSON, allocation results, memory CSV and JFR evidence remain attached; completed
round evidence survives a later failure. Text streams also have gzip copies.

The gate has only PASS, FAIL and INVALID; percentages and composite scores cannot
make missing evidence pass. Stored absolute results are evidence, never comparators.
The versioned [baseline registry](../../tests/performance/baselines.json) currently
has no release baseline. Once one is recorded, configuration cannot omit or replace
it. A first passing full run emits `first-baseline.json` plus a digest receipt;
issue #55 records the accepted release bundle. Replacement requires a reviewed
registry change with old/new digests, rationale, explicit maintainer approval and
complete qualification beside the old baseline. The runner never auto-rebaselines,
publishes assets, or bundles the Conformance Oracle executable.
